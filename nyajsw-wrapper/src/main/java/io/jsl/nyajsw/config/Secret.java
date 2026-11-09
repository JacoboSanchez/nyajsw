package io.jsl.nyajsw.config;

import java.util.Objects;

/**
 * A configuration value that must never be shown, such as {@code wrapper.ntservice.password}
 * (AGENTS.md §4.7). {@link #toString()} returns the mask, so a secret that ends up in a log line or
 * an exception message by accident still does not leak; code that needs the text asks for
 * {@link #reveal()} explicitly.
 */
public final class Secret {

    private final String value;

    public Secret(String value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    /** The secret text, for the one place that needs it (e.g. the call that installs the service). */
    public String reveal() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Secret secret && secret.value.equals(value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return SecretMasker.MASK;
    }
}
