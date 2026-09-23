package com.boeing.depgraph.fixtures;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * RUNTIME retention, so it survives into the class file.
 *
 * <p>A SOURCE-retention annotation - Lombok's, for instance - would be gone before the
 * bytecode existed and could not be detected at all.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {
}
