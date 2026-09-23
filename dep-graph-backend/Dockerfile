# dep-graph backend
# =================
# Runtime only: this image copies a jar that was built on the host.
#
# The sibling scanners build from source inside the image. That cannot work here without
# extra machinery, because every pom in this estate inherits com.boeing.aero:aero-parent,
# which lives in platform/ - outside this repository and therefore outside any build
# context rooted at it. An in-image build would need the aero root as its context, which
# means shipping every sibling repository's target/ and node_modules/ to the daemon.
#
# So the contract is: build the jar, then build the image.
#
#   mvn -B -DskipTests package
#   docker compose up -d --build
#
# scripts/depgraph-stack does both in the right order.

ARG JAVA_VERSION=21
FROM eclipse-temurin:${JAVA_VERSION}-jre

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# The scan reads whatever is under the allowed roots, so it should not read them as root.
RUN useradd --system --create-home --uid 10001 app

WORKDIR /app

# The Boot plugin leaves the pre-repackage jar as *.jar.original, so this glob matches
# only the executable one.
COPY dep-graph-backend/target/dep-graph-backend-*.jar app.jar

USER app

EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# No actuator dependency, so springdoc's api-docs is the cheapest endpoint that proves the
# context finished starting rather than just binding a socket.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS http://127.0.0.1:8080/v3/api-docs >/dev/null || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
