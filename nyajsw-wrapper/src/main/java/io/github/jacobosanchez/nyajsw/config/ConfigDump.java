package io.github.jacobosanchez.nyajsw.config;

import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A human-readable listing of the effective configuration for logs and diagnostics: one line per key
 * in {@link LoadedConfig} order, {@code key = value  [layer, location]}, with secrets hidden by
 * {@link SecretMasker}. This is the only sanctioned way to show configuration values.
 */
public final class ConfigDump {

    private ConfigDump() {
    }

    public static String render(KeyRegistry registry, LoadedConfig config) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(config, "config");
        SecretMasker masker = new SecretMasker(registry, config);
        return config.entries().stream()
                .map(entry -> entry.key() + " = " + masker.mask(entry.key(), entry.value()) + "  ["
                        + config.layer(entry.key()).orElseThrow().name().toLowerCase(Locale.ROOT)
                        + ", " + entry.location() + "]")
                .collect(Collectors.joining(System.lineSeparator()));
    }
}
