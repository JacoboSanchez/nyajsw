package io.github.jacobosanchez.nyajsw.config;

/**
 * Who defines a configuration key. Both kinds live in the {@code wrapper.*} namespace (decision D9);
 * the origin keeps the provenance visible in warnings and in the compatibility matrix.
 */
public enum KeyOrigin {

    /** A key documented by YAJSW, kept with its name and meaning. */
    YAJSW,

    /** A nyajsw extension that YAJSW does not know. */
    NYAJSW
}
