package com.boeing.depgraph.maven;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.resolution.InvalidRepositoryException;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Hands the model builder the parent and BOM poms it asks for.
 *
 * <p>Maven's {@code ModelBuilder} will happily flatten inheritance and imports, but it
 * does not know how to fetch anything — it calls back here for every
 * {@code <parent>} and every {@code scope=import} it meets. This is the smallest
 * implementation that is actually correct: resolve the artifact with type {@code pom} and
 * point a {@link FileModelSource} at the file that comes back.
 *
 * <p>Deliberately not Maven's own {@code ProjectModelResolver}. That class lives in
 * {@code maven-core} and drags in a Sisu container, a plugin manager and a lifecycle
 * executor, none of which are wanted by a tool whose entire relationship with Maven is
 * reading files.
 */
public class RepositoryModelResolver implements ModelResolver {

    private final RepositorySystem system;
    private final RepositorySystemSession session;
    private final List<RemoteRepository> repositories;

    /** Repositories already added, so a repeated {@code <repository>} does not pile up. */
    private final Set<String> repositoryIds = new LinkedHashSet<>();

    public RepositoryModelResolver(RepositorySystem system,
                                   RepositorySystemSession session,
                                   List<RemoteRepository> repositories) {
        this.system = system;
        this.session = session;
        this.repositories = new ArrayList<>(repositories);
        for (RemoteRepository repository : repositories) {
            repositoryIds.add(repository.getId());
        }
    }

    @Override
    public ModelSource resolveModel(String groupId, String artifactId, String version)
            throws UnresolvableModelException {
        Artifact pom = new DefaultArtifact(groupId, artifactId, "", "pom", version);
        try {
            var result = system.resolveArtifact(session, new ArtifactRequest(pom, repositories, null));
            return new FileModelSource(result.getArtifact().getFile());
        } catch (ArtifactResolutionException e) {
            throw new UnresolvableModelException(e.getMessage(), groupId, artifactId, version, e);
        }
    }

    @Override
    public ModelSource resolveModel(Parent parent) throws UnresolvableModelException {
        return resolveModel(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
    }

    @Override
    public ModelSource resolveModel(Dependency dependency) throws UnresolvableModelException {
        return resolveModel(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
    }

    /**
     * Repositories declared inside the poms being read are honoured.
     *
     * <p>A project that publishes its parent to an internal Nexus and says so in the pom
     * is the normal case, not an exotic one, and refusing to look there would mean every
     * module in it failed to build a model.
     */
    @Override
    public void addRepository(Repository repository) throws InvalidRepositoryException {
        addRepository(repository, false);
    }

    @Override
    public void addRepository(Repository repository, boolean replace) throws InvalidRepositoryException {
        if (!replace && !repositoryIds.add(repository.getId())) {
            return;
        }
        repositories.add(new RemoteRepository.Builder(
            repository.getId(), repository.getLayout(), repository.getUrl()).build());
    }

    /**
     * The builder copies the resolver per lineage so that repositories picked up while
     * reading one module do not leak into the next.
     */
    @Override
    public ModelResolver newCopy() {
        return new RepositoryModelResolver(system, session, repositories);
    }
}
