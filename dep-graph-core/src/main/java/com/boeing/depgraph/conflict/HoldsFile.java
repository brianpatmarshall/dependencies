package com.boeing.depgraph.conflict;

import com.boeing.depgraph.model.ArtifactKey;
import com.boeing.depgraph.model.VersionHold;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads {@code .depgraph-holds.yml} from the scan root.
 *
 * <pre>
 * holds:
 *   - artifact: io.opentelemetry:opentelemetry-bom
 *     version: 1.54.1
 *     reason: the observability module is written against 1.54.1's API
 *     until: when observability moves to the 1.49 API
 *
 *   - artifact: org.apache.pulsar:pulsar-client
 *     version: 3.3.1
 *     reason: 4.x changes the schema and subscription APIs; this is a migration, not a bump
 * </pre>
 *
 * <p>The file is optional and its absence is not a problem. Its presence is what stops
 * the tool from re-litigating settled decisions on every scan — a report whose loudest
 * finding is something the team chose deliberately last quarter is a report people learn
 * to close.
 */
public final class HoldsFile {

    private static final Logger log = LoggerFactory.getLogger(HoldsFile.class);

    public static final String FILE_NAME = ".depgraph-holds.yml";

    private HoldsFile() {
    }

    public static List<VersionHold> readFrom(Path scanRoot) {
        Path file = scanRoot.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            log.debug("No {} at {} - every version difference will be reported", FILE_NAME, scanRoot);
            return List.of();
        }
        try (InputStream in = Files.newInputStream(file)) {
            List<VersionHold> holds = parse(in);
            log.info("Read {} deliberate version hold(s) from {}", holds.size(), file);
            return holds;
        } catch (IOException | RuntimeException e) {
            // A malformed holds file must not fail a scan. The cost of ignoring it is
            // some noise in the report; the cost of failing is no report at all.
            log.warn("Ignoring unreadable {}: {}", file, e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    static List<VersionHold> parse(InputStream in) {
        Object loaded = new Yaml().load(in);
        if (!(loaded instanceof Map<?, ?> root)) {
            return List.of();
        }
        Object entries = root.get("holds");
        if (!(entries instanceof List<?> list)) {
            return List.of();
        }

        List<VersionHold> holds = new ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            String artifact = string(map.get("artifact"));
            String reason = string(map.get("reason"));
            if (artifact == null || !artifact.contains(":")) {
                log.warn("Skipping hold with no groupId:artifactId - {}", map);
                continue;
            }
            if (reason == null || reason.isBlank()) {
                // Enforced rather than defaulted. An unexplained hold is indistinguishable
                // from neglect, which is the one thing a hold must never look like.
                log.warn("Skipping hold on {} - a hold needs a reason", artifact);
                continue;
            }
            int colon = artifact.indexOf(':');
            holds.add(new VersionHold(
                new ArtifactKey(artifact.substring(0, colon).trim(), artifact.substring(colon + 1).trim()),
                string(map.get("version")),
                reason.trim(),
                string(map.get("until"))));
        }
        return List.copyOf(holds);
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
