package io.jsl.nyajsw.config;

/**
 * Where an effective configuration value comes from. Layers are listed from lowest to highest
 * precedence (plan §4.5, AGENTS.md §4.6): a higher layer overrides a lower one.
 */
public enum ConfigLayer {

    /** A constant default declared in the key registry. */
    DEFAULT,

    /** The configuration file or one of the files it includes. */
    FILE,

    /** A {@code -Dwrapper.*} system property of the wrapper JVM. */
    SYSTEM_PROPERTY
}
