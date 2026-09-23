package com.boeing.depgraph.backend.config;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * One driver for the application.
 *
 * <p>The driver is a connection pool, not a connection: it is thread-safe, expensive to
 * build and meant to be held for the process's lifetime.
 */
@Configuration
public class Neo4jConfig {

    @Bean(destroyMethod = "close")
    public Driver neo4jDriver(@Value("${neo4j.uri:bolt://localhost:7689}") String uri,
                              @Value("${neo4j.username:neo4j}") String username,
                              @Value("${neo4j.password:depgraph}") String password) {
        return GraphDatabase.driver(uri, AuthTokens.basic(username, password),
            org.neo4j.driver.Config.builder()
                // A scan writes a few hundred thousand relationships in batches. The
                // default acquisition timeout is fine for reads and too tight for the
                // tail of a large write.
                .withConnectionAcquisitionTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .withMaxConnectionLifetime(1, java.util.concurrent.TimeUnit.HOURS)
                .withMaxTransactionRetryTime(Duration.ofSeconds(30).toMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS)
                .build());
    }
}
