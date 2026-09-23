package com.boeing.depgraph.backend;

import com.boeing.depgraph.backend.config.DepGraphProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * The graph half of dep-graph.
 *
 * <p>The CLI answers "what is this repository on" without any of this. The backend exists
 * for the question the CLI cannot answer: what the two planes say when they are laid on
 * top of each other. That needs the whole estate in one place at once, which is what the
 * graph is for.
 */
@SpringBootApplication
@EnableConfigurationProperties(DepGraphProperties.class)
public class DepGraphBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(DepGraphBackendApplication.class, args);
    }
}
