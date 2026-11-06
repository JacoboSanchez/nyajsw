package io.github.jacobosanchez.nyajsw.config;

import java.util.Objects;

/**
 * The value a configuration key has when no layer sets it. Declared once, in the key registry, so the
 * default is never repeated elsewhere (plan §4.5).
 */
public sealed interface KeyDefault {

    /** The key has no default: when absent, the feature it controls is off or the key is not used. */
    KeyDefault NONE = new None();

    /** A fixed default, written as it would appear in a configuration file. */
    static KeyDefault of(String value) {
        return new Constant(value);
    }

    /**
     * A default that depends on other settings or on the environment, such as the configuration
     * file's directory or whether the agent is enabled. The description is the single documentation
     * of the rule; the typed configuration view that owns the key computes it.
     */
    static KeyDefault derived(String description) {
        return new Derived(description);
    }

    /** See {@link #NONE}. */
    record None() implements KeyDefault {
    }

    /** See {@link KeyDefault#of(String)}. */
    record Constant(String value) implements KeyDefault {

        public Constant {
            Objects.requireNonNull(value, "value");
        }
    }

    /** See {@link KeyDefault#derived(String)}. */
    record Derived(String description) implements KeyDefault {

        public Derived {
            Objects.requireNonNull(description, "description");
            if (description.isBlank()) {
                throw new IllegalArgumentException("derived default needs a description");
            }
        }
    }
}
