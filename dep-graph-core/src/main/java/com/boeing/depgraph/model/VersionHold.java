package com.boeing.depgraph.model;

/**
 * A version this project is staying on deliberately, and why.
 *
 * <p>Read from {@code .depgraph-holds.yml} at the scan root. Without it the tool is a
 * nag: every scan re-reports a decision that was already made, argued and written down,
 * and a report whose top finding is something you chose on purpose is a report people
 * stop opening.
 *
 * <p>This estate is its own first example. {@code aero-bom} holds
 * {@code opentelemetry-bom} at 1.54.1 against Spring Boot's 1.49.0 because the
 * observability module is written to 1.54.1's API, and {@code pulsar-client} at 3.3.1
 * against Boot's 4.0.11 because 4.x is an API migration. Both have the reasoning in a
 * comment beside them. A hold is that comment, in a form the tool can read.
 *
 * @param version the version being held, or null to hold whatever is currently in use
 * @param reason  why. Required: a hold with no reason is indistinguishable from neglect,
 *                which is the thing holds exist to be distinguishable from
 * @param until   an ISO date or a free-text milestone after which the hold should be
 *                re-argued, or null for an indefinite one
 */
public record VersionHold(
    ArtifactKey key,
    String version,
    String reason,
    String until
) {

    public boolean appliesTo(ArtifactKey candidate) {
        return key.equals(candidate);
    }

    /** A hold that names no version pins whatever the project is on today. */
    public boolean pinsAnyVersion() {
        return version == null || version.isBlank();
    }
}
