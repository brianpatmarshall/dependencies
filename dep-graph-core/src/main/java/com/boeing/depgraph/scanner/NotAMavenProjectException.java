package com.boeing.depgraph.scanner;

import java.nio.file.Path;

/**
 * The path handed to the scanner is not something this tool can analyse.
 *
 * <p>Its own exception rather than a generic one because this is the single most likely
 * thing to go wrong when a person types a path into a command line or a text field, and
 * the difference between a good tool and an irritating one is whether the message says
 * what was wrong with what they typed.
 *
 * <p>Explicitly <em>not</em> an empty result. A scan that finds no poms and reports
 * success with zero of everything is indistinguishable from a project with no
 * dependencies, and the user will believe the second one.
 */
public class NotAMavenProjectException extends RuntimeException {

    private final transient Path path;

    public NotAMavenProjectException(Path path, String reason) {
        super(reason);
        this.path = path;
    }

    public Path path() {
        return path;
    }
}
