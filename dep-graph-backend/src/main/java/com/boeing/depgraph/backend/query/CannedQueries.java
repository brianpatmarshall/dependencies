package com.boeing.depgraph.backend.query;

import com.boeing.depgraph.backend.query.CannedQuery.Category;
import com.boeing.depgraph.backend.query.CannedQuery.Param;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The catalogue.
 *
 * <p>Ordered so that the questions people arrive with come first and the ones that need
 * the whole machine working come last. The {@link Category#BRIDGE} group is the reason the
 * backend exists: everything in it needs both planes and none of it can be answered by
 * reading poms or bytecode alone.
 */
public final class CannedQueries {

    private static final List<CannedQuery> ALL = List.of(

        // ------------------------------------------------------------ versions
        new CannedQuery("version-conflicts", Category.VERSIONS,
            "Version conflicts",
            "Which artifacts are on more than one version, and who is on which?",
            """
            MATCH (a:Artifact {conflict: true})<-[:VERSION_OF]-(v:ArtifactVersion)
            OPTIONAL MATCH (m:Module)-[:DECLARES_DEP]->(v)
            WITH a, v.version AS version, collect(DISTINCT m.label) AS modules
            WHERE size(modules) > 0
            RETURN a.ga AS artifact, a.severityLabel AS severity, version,
                   size(modules) AS moduleCount, modules
            ORDER BY a.severity, artifact, version
            """,
            List.of(), null, false),

        new CannedQuery("pinned-over-bom", Category.VERSIONS,
            "Hand-pinned over a BOM",
            "Where has a module written a version of its own for something a BOM already manages?",
            """
            MATCH (m:Module)-[d:DECLARES_DEP]->(v:ArtifactVersion)-[:VERSION_OF]->(a:Artifact)
            WHERE a.severity = 'ALREADY_MANAGED' AND d.origin = 'LOCAL_DECLARATION'
            RETURN a.ga AS artifact, a.managedBy AS managedBy, v.version AS pinnedVersion,
                   m.label AS module, d.rawVersion AS writtenAs
            ORDER BY artifact, module
            """,
            List.of(),
            "This is the mechanism by which an estate drifts apart one upgrade at a time: "
                + "each pin is individually reasonable and collectively how two modules end "
                + "up on different majors.", false),

        new CannedQuery("bom-proposal", Category.VERSIONS,
            "The proposed BOM",
            "What would a generated dependencyManagement block contain?",
            """
            MATCH (e:BomEntry)
            RETURN e.ga AS artifact, e.version AS version, e.needsReview AS needsReview,
                   e.rationale AS rationale, e.replaces AS replaces, size(e.modules) AS modules
            ORDER BY e.needsReview DESC, artifact
            """,
            List.of(),
            "Major-version skew is excluded by design - that is an API migration, not a "
                + "version bump, and a BOM that silently performed one would be a trap.", false),

        new CannedQuery("holds", Category.VERSIONS,
            "Deliberate holds",
            "Which versions are we staying on deliberately, and why?",
            """
            MATCH (a:Artifact) WHERE a.held
            RETURN a.ga AS artifact, a.holdVersion AS heldAt,
                   a.holdReason AS reason, a.holdUntil AS until
            ORDER BY artifact
            """,
            List.of(),
            "A hold with no written reason is ignored by the analyser on purpose: an "
                + "unexplained hold is indistinguishable from neglect.", false),

        new CannedQuery("artifact-versions", Category.VERSIONS,
            "Every version of one artifact",
            "Who declares this artifact, at what version, and what decided it?",
            """
            MATCH (a:Artifact {ga: $ga})<-[:VERSION_OF]-(v:ArtifactVersion)
            OPTIONAL MATCH (m:Module)-[d:DECLARES_DEP]->(v)
            RETURN v.version AS version, m.label AS module, d.scope AS scope,
                   d.originLabel AS decidedBy, d.rawVersion AS writtenAs
            ORDER BY version DESC, module
            """,
            List.of(new Param("ga", "groupId:artifactId", "org.slf4j:slf4j-api")),
            null, false),

        new CannedQuery("version-origins", Category.VERSIONS,
            "How versions get decided",
            "Across the whole scan, what actually decides a dependency's version?",
            """
            MATCH (:Module)-[d:DECLARES_DEP]->(:ArtifactVersion)
            RETURN d.originLabel AS decidedBy, count(*) AS dependencies
            ORDER BY dependencies DESC
            """,
            List.of(),
            "A healthy estate is mostly 'Managed by an imported BOM'. A lot of 'Declared "
                + "here' is the shape of a project about to disagree with itself.", false),

        // ------------------------------------------------------------ topology
        new CannedQuery("module-dependencies", Category.TOPOLOGY,
            "Module dependencies",
            "Which modules in this scan depend on each other?",
            """
            MATCH (m:Module)-[r:DEPENDS_ON_MODULE]->(t:Module)
            RETURN m.label AS module, t.label AS dependsOn, r.gav AS atVersion
            ORDER BY module, dependsOn
            """,
            List.of(),
            "Derived, not declared. Nothing in a pom says 'this dependency is one of us' - "
                + "the answer changes with the scan root.", false),

        new CannedQuery("bom-imports", Category.TOPOLOGY,
            "BOM imports",
            "Which modules import which BOMs?",
            """
            MATCH (m:Module)-[r:IMPORTS_BOM]->(x)
            RETURN m.label AS module, r.gav AS bom, r.inProject AS insideScan
            ORDER BY bom, module
            """,
            List.of(),
            "The single most important edge for explaining why a version is what it is.", false),

        new CannedQuery("external-parents", Category.TOPOLOGY,
            "Parents outside the scan",
            "Which poms inherit from something with no pom on disk here?",
            """
            MATCH (m:Module)-[r:PARENT]->(x)
            WHERE r.inProject = false
            RETURN m.label AS module, r.gav AS parent
            ORDER BY parent, module
            """,
            List.of(),
            "Normal, not broken. Every repository in this estate inherits aero-parent, "
                + "which is resolved from the artifact repository.", false),

        new CannedQuery("module-fan", Category.TOPOLOGY,
            "Module fan-in and fan-out",
            "Which modules does everything depend on, and which depend on everything?",
            """
            MATCH (m:Module)
            OPTIONAL MATCH (m)-[:DEPENDS_ON_MODULE]->(downstream:Module)
            OPTIONAL MATCH (upstream:Module)-[:DEPENDS_ON_MODULE]->(m)
            WITH m, count(DISTINCT downstream) AS fanOut, count(DISTINCT upstream) AS fanIn
            WHERE fanIn + fanOut > 0
            RETURN m.label AS module, fanIn, fanOut,
                   round(toFloat(fanOut) / (fanIn + fanOut), 2) AS instability
            ORDER BY fanIn DESC, module
            """,
            List.of(),
            "Instability is Martin's I = fanOut / (fanIn + fanOut). 0 is depended upon by "
                + "everything and depends on nothing; 1 is the opposite.", false),

        new CannedQuery("module-cycles", Category.TOPOLOGY,
            "Module cycles",
            "Do any modules depend on each other in a loop?",
            """
            MATCH path = (m:Module)-[:DEPENDS_ON_MODULE*2..6]->(m)
            RETURN [n IN nodes(path) | n.label] AS cycle, length(path) AS hops
            ORDER BY hops, cycle
            LIMIT 50
            """,
            List.of(),
            "Maven cannot build a module cycle, so a result here means the cycle runs "
                + "through something the reactor does not see as one.", false),

        // ------------------------------------------------------------ the bridge
        new CannedQuery("unused-dependencies", Category.BRIDGE,
            "Declared but never used",
            "Which dependencies does a module declare without any of its code touching them?",
            """
            MATCH (m:Module)-[d:DECLARES_DEP]->(v:ArtifactVersion)-[:VERSION_OF]->(a:Artifact)
            WHERE m.built = true AND d.testOnly = false AND d.optional = false
              AND EXISTS { (:Type)-[:FROM]->(v) }
              AND NOT (m)-[:USES_CODE]->(:ArtifactVersion)-[:VERSION_OF]->(a)
              AND NOT (m)-[:USES_CODE]->(:Module {ga: a.ga})
              AND NOT EXISTS {
                    MATCH (m)-[:RESOLVES {viaGa: a.ga}]->(brought:ArtifactVersion)
                    WHERE (m)-[:USES_CODE]->(brought)
              }
            RETURN m.label AS module, a.ga AS declaredButUnused,
                   v.version AS version, d.scope AS scope
            ORDER BY module, declaredButUnused
            """,
            List.of(),
            "A question, not a verdict. javac copies `static final` constants into their "
                + "callers, leaving no reference to the declaring class, so a module whose "
                + "only use of a library is a constant looks unused. Annotations that are "
                + "not retained at runtime and anything reached only by reflection are "
                + "invisible here too. Matched on groupId:artifactId rather than the exact "
                + "version, so declaring a different version from the one another module "
                + "pulled in does not read as unused. The bigger gap is transitive: only "
                + "directly declared jars are read, so a library whose API type lives in a "
                + "companion artifact - pulsar-client's interfaces are in pulsar-client-api "
                + "- is credited for what it brought in, via RESOLVES. What remains is "
                + "genuinely unreferenced by compiled code, which for a runtime binding like "
                + "logback-classic is correct and expected. Check 'Classes referenced but "
                + "never read' before believing a row.", true),

        new CannedQuery("undeclared-usage", Category.BRIDGE,
            "Used but never declared",
            "Whose code is living on somebody else's transitive dependency?",
            """
            MATCH (m:Module)-[u:USES_CODE]->(v:ArtifactVersion)-[:VERSION_OF]->(a:Artifact)
            WHERE NOT (m)-[:DECLARES_DEP]->(:ArtifactVersion)-[:VERSION_OF]->(a)
            WITH m, a, sum(u.weight) AS weight,
                 max(u.fromTypes) AS ourTypes, sum(u.toTypes) AS theirTypes
            RETURN m.label AS module, a.ga AS usedButNotDeclared,
                   ourTypes, theirTypes, weight
            ORDER BY weight DESC
            """,
            List.of(),
            "Each row is code that compiles today and breaks the moment somebody else's "
                + "dependency tree changes underneath it. Compared artifact by artifact "
                + "rather than version by version on purpose: one class is one node, so a "
                + "type read from two versions of the same library bridges to both, and a "
                + "version-level comparison would call every such library undeclared.", true),

        new CannedQuery("blast-radius", Category.BRIDGE,
            "Blast radius of an artifact",
            "If this library changed, which modules and how many of our types would feel it?",
            """
            MATCH (a:Artifact {ga: $ga})<-[:VERSION_OF]-(:ArtifactVersion)<-[:FROM]-(lib:Type)
            MATCH (ours:Type)-[d:DEPENDS_ON]->(lib)
            WHERE ours.thirdParty = false
            OPTIONAL MATCH (ours)-[:FROM]->(m:Module)
            RETURN coalesce(m.label, '(unattributed)') AS module,
                   count(DISTINCT ours) AS ourTypesAffected,
                   count(DISTINCT lib) AS theirTypesTouched,
                   sum(d.weight) AS weight
            ORDER BY ourTypesAffected DESC
            """,
            List.of(new Param("ga", "groupId:artifactId", "org.slf4j:slf4j-api")),
            null, true),

        new CannedQuery("who-uses-artifact", Category.BRIDGE,
            "Exactly which of our classes touch a library",
            "Type by type, how is this artifact actually used?",
            """
            MATCH (a:Artifact {ga: $ga})<-[:VERSION_OF]-(:ArtifactVersion)<-[:FROM]-(lib:Type)
            MATCH (ours:Type)-[d:DEPENDS_ON]->(lib)
            WHERE ours.thirdParty = false
            RETURN ours.fqn AS ourType, lib.fqn AS libraryType,
                   d.kinds AS how, d.weight AS weight
            ORDER BY weight DESC
            """,
            List.of(new Param("ga", "groupId:artifactId", "org.slf4j:slf4j-api")),
            "`how` lists the relationship kinds behind the edge. A dependency that is only "
                + "PARAM and RETURNS is a seam; one that is EXTENDS is a commitment.", true),

        new CannedQuery("code-vs-declared", Category.BRIDGE,
            "Code use versus declared dependency",
            "Which modules use each other's code, and did the pom say so?",
            """
            MATCH (m:Module)-[u:USES_CODE]->(t:Module)
            OPTIONAL MATCH (m)-[dep:DEPENDS_ON_MODULE]->(t)
            RETURN m.label AS module, t.label AS uses, u.weight AS codeWeight,
                   u.fromTypes AS ourTypes, (dep IS NOT NULL) AS declared
            ORDER BY declared, codeWeight DESC
            """,
            List.of(),
            "The rows with declared = false are the finding. Everything else is the tool "
                + "agreeing with the build.", true),

        new CannedQuery("unattributed-types", Category.BRIDGE,
            "Types the bridge could not place",
            "Which classes came from a path that is neither a module nor a declared jar?",
            """
            MATCH (t:Type)
            WHERE NOT (t)-[:FROM]->()
            RETURN t.originPath AS origin, count(*) AS types,
                   collect(t.fqn)[0..5] AS examples
            ORDER BY types DESC
            LIMIT 50
            """,
            List.of(),
            "Usually a transitive jar: it is on the classpath without being any module's "
                + "declared dependency, so nothing in the build plane names it.", true),

        new CannedQuery("transitive-origin", Category.BRIDGE,
            "Why is this jar on my classpath?",
            "Which direct dependency brought this artifact in, and at what depth?",
            """
            MATCH (m:Module)-[c:RESOLVES]->(v:ArtifactVersion)-[:VERSION_OF]->(a:Artifact {ga: $ga})
            RETURN m.label AS module, v.version AS version, c.depth AS depth,
                   CASE c.depth WHEN 0 THEN '(declared directly)' ELSE c.viaGa END AS broughtInBy,
                   c.scope AS scope
            ORDER BY depth, module
            """,
            List.of(new Param("ga", "groupId:artifactId", "org.apache.pulsar:pulsar-client-api")),
            "RESOLVES is the classpath Maven would build, which is a longer list than "
                + "DECLARES_DEP - the poms. depth 0 means the module names it itself.", false),

        // ------------------------------------------------------------ code
        new CannedQuery("most-depended-types", Category.CODE,
            "Most depended-upon types",
            "Which of our classes would hurt most to change?",
            """
            MATCH (t:Type)<-[d:DEPENDS_ON]-(other:Type)
            WHERE t.thirdParty = false
            RETURN t.fqn AS type, t.kind AS kind,
                   count(DISTINCT other) AS dependents, sum(d.weight) AS weight
            ORDER BY dependents DESC
            LIMIT 50
            """,
            List.of(), null, true),

        new CannedQuery("god-classes", Category.CODE,
            "Classes that reach for everything",
            "Which of our classes depend on the most other types?",
            """
            MATCH (t:Type)-[d:DEPENDS_ON]->(other:Type)
            WHERE t.thirdParty = false
            RETURN t.fqn AS type, t.kind AS kind,
                   count(DISTINCT other) AS dependsOn, sum(d.weight) AS weight
            ORDER BY dependsOn DESC
            LIMIT 50
            """,
            List.of(),
            "High fan-out is not automatically wrong - a wiring class or a test fixture "
                + "does this legitimately. It is a place to look, not a defect.", true),

        new CannedQuery("package-cycles", Category.CODE,
            "Package cycles",
            "Which of our packages depend on each other in both directions?",
            """
            MATCH (a:Type)-[:DEPENDS_ON]->(b:Type)
            WHERE a.thirdParty = false AND b.thirdParty = false
              AND a.packageName <> b.packageName AND a.packageName < b.packageName
            WITH DISTINCT a.packageName AS packageA, b.packageName AS packageB
            MATCH (x:Type {packageName: packageB})-[:DEPENDS_ON]->(y:Type {packageName: packageA})
            RETURN packageA, packageB, count(*) AS edgesBack
            ORDER BY edgesBack DESC
            LIMIT 50
            """,
            List.of(), null, true),

        new CannedQuery("relationship-mix", Category.CODE,
            "What kinds of coupling exist",
            "Across the whole graph, which relationship types are actually in use?",
            """
            MATCH (:Type)-[r]->(:Type)
            WHERE type(r) <> 'DEPENDS_ON'
            RETURN type(r) AS relationship, count(*) AS edges, sum(r.count) AS occurrences
            ORDER BY edges DESC
            """,
            List.of(),
            "Counts only relationships with our own code at one end, which is all that is "
                + "written by default. Library types are also read surface-only, so BODY "
                + "relations (CALLS, INSTANTIATES) are almost entirely ours.", true),

        new CannedQuery("inheritance-depth", Category.CODE,
            "Deepest inheritance chains",
            "Which of our classes sit furthest down a hierarchy?",
            """
            MATCH path = (t:Type)-[:EXTENDS*1..10]->(root:Type)
            WHERE t.thirdParty = false AND NOT (root)-[:EXTENDS]->(:Type)
            RETURN t.fqn AS type, length(path) AS depth,
                   [n IN nodes(path) | n.simpleName] AS chain
            ORDER BY depth DESC, type
            LIMIT 25
            """,
            List.of(),
            "Chains stop at the first library class by default, because relationships "
                + "between two library types are not written (depgraph.include-library-edges). "
                + "This measures depth within our own hierarchy, not into the JDK.", true),

        new CannedQuery("types-between-modules", Category.CODE,
            "What couples two modules",
            "Exactly which classes tie one module to another?",
            """
            MATCH (a:Type)-[:FROM]->(m1:Module {label: $from})
            MATCH (b:Type)-[:FROM]->(m2:Module {label: $to})
            MATCH (a)-[d:DEPENDS_ON]->(b)
            RETURN a.fqn AS fromType, b.fqn AS toType, d.kinds AS how, d.weight AS weight
            ORDER BY weight DESC
            LIMIT 200
            """,
            List.of(new Param("from", "From module (label)", "dependencies/dep-graph-backend/pom.xml"),
                    new Param("to", "To module (label)", "dependencies/dep-graph-core/pom.xml")),
            "A module label is its pom path relative to the scan root. Run "
                + "'Module dependencies' first if you are not sure what to type.", true),

        // ------------------------------------------------------------ health
        new CannedQuery("scan-problems", Category.HEALTH,
            "Poms that would not read",
            "Which poms failed to build a model, and why?",
            """
            MATCH (:Project)-[:HAD_PROBLEM]->(x:ScanProblem)
            RETURN x.relativePath AS pom, x.messages AS messages
            ORDER BY pom
            """,
            List.of(),
            "A conflict table built from a scan that silently dropped poms is confidently "
                + "wrong, which is why these are in the graph rather than a log line.", false),

        new CannedQuery("unbuilt-modules", Category.HEALTH,
            "Modules with no compiled classes",
            "Which modules contributed nothing to the code plane?",
            """
            MATCH (m:Module) WHERE m.built = false
            RETURN m.label AS module, m.relativePath AS path, m.packaging AS packaging
            ORDER BY module
            """,
            List.of(),
            "The one price of reading bytecode instead of source. An aggregator or a BOM "
                + "legitimately has no classes; a jar module here has simply not been built.", true),

        new CannedQuery("silent-dependencies", Category.HEALTH,
            "Declared jars that contributed no types",
            "Which declared dependencies put nothing into the code plane at all?",
            """
            MATCH (m:Module)-[:DECLARES_DEP]->(v:ArtifactVersion)
            WHERE NOT EXISTS { (:Type)-[:FROM]->(v) }
            RETURN v.gav AS dependency, count(DISTINCT m) AS declaredBy,
                   collect(DISTINCT m.label)[0..4] AS modules
            ORDER BY dependency
            """,
            List.of(),
            "Three different things land here and they mean different things: an aggregator "
                + "jar that really is empty (netty-all), a module of this project whose classes "
                + "are read from target/classes instead, and a jar that was never found in the "
                + "local repository. Only the last is a problem, and nothing about an artifact "
                + "in this list can be concluded from the code plane.", true),

        new CannedQuery("missing-classes", Category.HEALTH,
            "Classes referenced but never read",
            "Which packages does our code reference that the scan never resolved?",
            """
            MATCH (p:MissingPackage)
            RETURN p.name AS package, p.references AS references,
                   p.types AS distinctTypes, p.examples AS examples
            ORDER BY references DESC
            LIMIT 50
            """,
            List.of(),
            "Every row is a gap in the graph, and the reason an 'unused dependency' finding "
                + "needs checking: an edge whose target was never read is not written, so the "
                + "evidence of use disappears. Usually a jar missing from the local "
                + "repository, or a module that fell back to its direct dependencies because "
                + "a transitive pom could not be resolved offline.", true),

        new CannedQuery("graph-overview", Category.HEALTH,
            "What is in the graph",
            "How much of each thing did this scan write?",
            """
            MATCH (n)
            WITH labels(n)[0] AS label, count(*) AS nodes
            RETURN label, nodes
            ORDER BY nodes DESC
            """,
            List.of(), null, false)
    );

    private static final Map<String, CannedQuery> BY_ID = new LinkedHashMap<>();

    static {
        for (CannedQuery query : ALL) {
            BY_ID.put(query.id(), query);
        }
    }

    private CannedQueries() {
    }

    public static List<CannedQuery> all() {
        return ALL;
    }

    public static Optional<CannedQuery> byId(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }
}
