package com.boeing.depgraph.conflict;

import com.boeing.depgraph.model.ConflictSeverity;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Ordering and skew classification for Maven version strings.
 *
 * <p>Version comparison is a place where the obvious implementation is wrong in ways that
 * matter. {@code String.compareTo} puts {@code 1.10} before {@code 1.9}; splitting on dots
 * and parsing ints falls over on {@code 6.7.0.202309050840-r}, which is a real version of
 * a library this estate depends on. Maven ships the algorithm its own resolver uses, and
 * a tool that proposes version bumps has no business disagreeing with it.
 */
public final class VersionOrder {

    /** Newest first — the order a conflict table wants. */
    public static final Comparator<String> DESCENDING =
        Comparator.comparing(ComparableVersion::new).reversed();

    /**
     * A version whose leading component is a number, and so has a comparable major.
     *
     * <p>{@code RELEASE}, {@code LATEST} and a bare timestamp have no major version to
     * speak of. They are classified conservatively rather than guessed at.
     */
    private static final Pattern NUMERIC_LEAD = Pattern.compile("^\\d+(\\.\\d+)*.*");

    private VersionOrder() {
    }

    public static String highest(Collection<String> versions) {
        return versions.stream().max(Comparator.comparing(ComparableVersion::new))
            .orElseThrow(() -> new IllegalArgumentException("No versions to compare"));
    }

    public static List<String> descending(Collection<String> versions) {
        return versions.stream().distinct().sorted(DESCENDING).toList();
    }

    /**
     * How far apart a set of versions is, read as semantic versioning.
     *
     * <p>One version is {@link ConflictSeverity#UNIFORM}. Otherwise only the extremes
     * matter: if the lowest and the highest agree on the major, no pair between them can
     * disagree on it, so comparing every pair would cost more and say the same thing.
     *
     * <p>Anything that does not start with a number is {@link ConflictSeverity#REVIEW},
     * never {@code SAFE}. The severities exist so that most rows can be applied without
     * anyone reading them, and that bargain only holds if a version the tool could not
     * parse is never one of them.
     */
    public static ConflictSeverity classify(Collection<String> versions) {
        List<String> distinct = versions.stream().distinct().toList();
        if (distinct.size() <= 1) {
            return ConflictSeverity.UNIFORM;
        }
        if (distinct.stream().anyMatch(v -> !NUMERIC_LEAD.matcher(v).matches())) {
            return ConflictSeverity.REVIEW;
        }

        List<String> sorted = distinct.stream().sorted(Comparator.comparing(ComparableVersion::new)).toList();
        DefaultArtifactVersion lowest = new DefaultArtifactVersion(sorted.get(0));
        DefaultArtifactVersion highest = new DefaultArtifactVersion(sorted.get(sorted.size() - 1));

        if (lowest.getMajorVersion() != highest.getMajorVersion()) {
            return ConflictSeverity.BLOCKED;
        }
        if (lowest.getMinorVersion() != highest.getMinorVersion()) {
            return ConflictSeverity.REVIEW;
        }
        return ConflictSeverity.SAFE;
    }
}
