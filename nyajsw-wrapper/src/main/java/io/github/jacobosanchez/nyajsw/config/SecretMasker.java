package io.github.jacobosanchez.nyajsw.config;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Hides secrets wherever a configuration value could be shown (AGENTS.md §4.7). The value of a
 * secret key is replaced as a whole; in every other value, each run of characters covered by an
 * occurrence of a secret's text is replaced by one mask, because <code>${...}</code> can copy a
 * password into a key that is not secret ({@code a=${wrapper.app.password}}), including secrets that
 * come from outside the configuration ({@link LoadedConfig#externalSecrets()}).
 *
 * <p>Known trade-off of matching text: a short or common secret also masks unrelated text, and the
 * mask's position can hint at the secret (a password {@code Java} shows as {@code C:\****\bin}).
 * Tracking which values were built from secrets during interpolation would avoid both; see the
 * plan's deferred findings.
 */
public final class SecretMasker {

    /** What a hidden secret looks like. */
    public static final String MASK = "****";

    private final KeyRegistry registry;
    /** Distinct non-empty secret values. */
    private final List<String> secrets;

    /**
     * @param registry decides which keys are secret
     * @param config   the configuration whose secret values are hidden
     */
    public SecretMasker(KeyRegistry registry, LoadedConfig config) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.secrets = Stream.concat(
                        config.entries().stream()
                                .filter(entry -> registry.isSecret(entry.key()) && !entry.value().isEmpty())
                                .map(ConfigEntry::value),
                        config.externalSecrets().stream().map(Secret::reveal))
                .distinct()
                .toList();
    }

    /** {@code value} as it may be shown for {@code key}. */
    public String mask(String key, String value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        if (registry.isSecret(key)) {
            return MASK;
        }
        // Mark every character of every occurrence first, so overlapping secrets are hidden whole.
        boolean[] hidden = new boolean[value.length()];
        boolean any = false;
        for (String secret : secrets) {
            for (int at = value.indexOf(secret); at >= 0; at = value.indexOf(secret, at + 1)) {
                Arrays.fill(hidden, at, at + secret.length(), true);
                any = true;
            }
        }
        if (!any) {
            return value;
        }
        StringBuilder shown = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            if (!hidden[i]) {
                shown.append(value.charAt(i));
            } else if (i == 0 || !hidden[i - 1]) {
                shown.append(MASK);
            }
        }
        return shown.toString();
    }
}
