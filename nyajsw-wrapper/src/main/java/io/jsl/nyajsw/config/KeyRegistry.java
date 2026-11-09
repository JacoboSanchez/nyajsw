package io.jsl.nyajsw.config;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * An immutable set of {@link KeySpec}s that finds the declaration a concrete configuration key belongs
 * to. {@link ConfigKeys#registry()} is the registry of every key nyajsw knows; tests build smaller
 * ones.
 *
 * <p>Lookup precedence: an exact name wins over any template; among templates the more specific one
 * wins (see {@link KeyPattern#MOST_SPECIFIC_FIRST}). The constructor rejects duplicate names, two
 * equally specific templates that can match the same key (so precedence never picks between equals
 * by accident), and declarations whose own example key resolves to another declaration (so every
 * declaration is reachable). A consequence of the last rule: a free placeholder must not be labelled
 * like a sibling exact key, e.g. {@code wrapper.on_exit.<default>} next to
 * {@code wrapper.on_exit.default}.
 */
public final class KeyRegistry {

    private final List<KeySpec> specs;
    private final Map<String, KeySpec> exact;
    /** Templates, most specific first. */
    private final List<KeySpec> templates;

    /**
     * @param specs the declarations, in documentation order
     * @throws IllegalArgumentException if two declarations have the same name, two equally specific
     *                                  templates overlap, or a declaration is shadowed by another one
     */
    public KeyRegistry(List<KeySpec> specs) {
        this.specs = List.copyOf(specs);
        Map<String, KeySpec> byName = new HashMap<>();
        for (KeySpec spec : this.specs) {
            if (byName.putIfAbsent(spec.name(), spec) != null) {
                throw new IllegalArgumentException("key declared twice: " + spec.name());
            }
        }
        this.exact = byName.entrySet().stream()
                .filter(entry -> entry.getValue().pattern().isExact())
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        this.templates = this.specs.stream()
                .filter(spec -> !spec.pattern().isExact())
                .sorted(Comparator.comparing(KeySpec::pattern, KeyPattern.MOST_SPECIFIC_FIRST))
                .toList();
        rejectOverlappingEquals(templates);
        for (KeySpec spec : this.specs) {
            String example = spec.pattern().example();
            KeySpec found = lookup(example).orElseThrow();
            if (found != spec) {
                throw new IllegalArgumentException("declaration " + spec.name() + " is shadowed by "
                        + found.name() + " (both match " + example + ")");
            }
        }
    }

    private static void rejectOverlappingEquals(List<KeySpec> templates) {
        for (int i = 0; i < templates.size(); i++) {
            KeyPattern first = templates.get(i).pattern();
            for (int j = i + 1; j < templates.size(); j++) {
                KeyPattern second = templates.get(j).pattern();
                if (KeyPattern.MOST_SPECIFIC_FIRST.compare(first, second) != 0) {
                    break; // sorted: no later template is as specific as the first one
                }
                first.commonExample(second).ifPresent(key -> {
                    throw new IllegalArgumentException("templates " + first + " and " + second
                            + " are equally specific and both match " + key);
                });
            }
        }
    }

    /** All declarations in documentation order, unmodifiable. */
    public List<KeySpec> specs() {
        return specs;
    }

    /** The declaration that {@code key} belongs to, or empty for a key nyajsw does not know. */
    public Optional<KeySpec> lookup(String key) {
        Objects.requireNonNull(key, "key");
        KeySpec spec = exact.get(key);
        if (spec != null) {
            return Optional.of(spec);
        }
        for (KeySpec template : templates) {
            if (template.matches(key)) {
                return Optional.of(template);
            }
        }
        return Optional.empty();
    }

    /**
     * Whether the value of {@code key} must be masked: the key is declared secret, or its name ends in
     * {@code .password} (compared case-insensitively). The name rule also covers unknown keys, so a
     * misspelt or future password key is never shown either.
     */
    public boolean isSecret(String key) {
        return KeySpec.isPasswordName(key) || lookup(key).map(KeySpec::secret).orElse(false);
    }
}
