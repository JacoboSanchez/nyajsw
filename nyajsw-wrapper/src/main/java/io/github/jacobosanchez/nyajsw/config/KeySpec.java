package io.github.jacobosanchez.nyajsw.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The declaration of one configuration key, or of one family of keys such as
 * {@code wrapper.java.additional.<n>}: everything parsing, validation, warnings, secret masking and
 * the generated compatibility matrix need to know about it (plan §4.5, decision D8). Instances are
 * created with {@link #yajsw} or {@link #nyajsw} and declared once, in {@link ConfigKeys}.
 *
 * @param pattern      the key name or template
 * @param type         the value type
 * @param defaultValue the value when no layer sets the key
 * @param origin       whether YAJSW defines the key or it is a nyajsw extension
 * @param status       how far nyajsw supports the key
 * @param phase        the plan phase that implements the key (e.g. {@code P2}); required for
 *                     {@link KeyStatus#PLANNED}, optional for unsupported keys that a backlog item
 *                     covers, empty otherwise
 * @param category     the topic used to group the key in documentation
 * @param secret       whether the value must be masked wherever it could be shown
 * @param description  one line saying what the key does
 * @param notes        caveats in prose; empty when there are none
 * @param unverified   parts of a YAJSW declaration still to be confirmed (R11); empty for nyajsw
 *                     extensions
 * @param values       for {@link KeyType#ENUM}: the canonical values in documentation order; empty for
 *                     other types
 * @param aliases      for {@link KeyType#ENUM}: other accepted spellings mapped to their canonical
 *                     value (e.g. {@code DELAY_START} to {@code DELAYED_AUTO_START}), in declaration
 *                     order; empty for other types
 */
public record KeySpec(
        KeyPattern pattern,
        KeyType type,
        KeyDefault defaultValue,
        KeyOrigin origin,
        KeyStatus status,
        String phase,
        KeyCategory category,
        boolean secret,
        String description,
        String notes,
        Set<Unverified> unverified,
        List<String> values,
        Map<String, String> aliases) {

    private static final String PASSWORD_SUFFIX = ".password";
    private static final Pattern PHASE = Pattern.compile("P[0-9]+");
    private static final Pattern SIZE_VALUE = Pattern.compile("[0-9]+[KkMmGg]?");

    public KeySpec {
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(defaultValue, "defaultValue");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(notes, "notes");
        unverified = unverified.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(unverified));
        values = List.copyOf(values);
        // Declaration order matters for generated documentation, so no Map.copyOf here.
        aliases = Collections.unmodifiableMap(new LinkedHashMap<>(aliases));
        String name = pattern.template();
        if (description.isBlank()) {
            throw invalid(name, "description is required");
        }
        if (status == KeyStatus.PLANNED && phase.isEmpty()) {
            throw invalid(name, "a planned key needs the phase that implements it");
        }
        if ((status == KeyStatus.SUPPORTED || status == KeyStatus.IGNORED) && !phase.isEmpty()) {
            throw invalid(name, "a " + status.label() + " key has no phase");
        }
        if (!phase.isEmpty() && !PHASE.matcher(phase).matches()) {
            throw invalid(name, "phase must look like P2: " + phase);
        }
        if (origin == KeyOrigin.NYAJSW && !unverified.isEmpty()) {
            throw invalid(name, "a nyajsw extension has nothing to verify against YAJSW");
        }
        if (isPasswordName(name) && !secret) {
            throw invalid(name, "keys named *" + PASSWORD_SUFFIX + " must be secret");
        }
        if (secret && defaultValue instanceof KeyDefault.Constant) {
            throw invalid(name, "a secret key must not have a constant default");
        }
        checkValues(name, type, values, aliases);
        checkDefault(name, type, defaultValue, values);
    }

    /** Starts the declaration of a key that YAJSW defines. */
    public static Builder yajsw(String template, KeyType type, KeyCategory category) {
        return new Builder(template, type, category, KeyOrigin.YAJSW);
    }

    /** Starts the declaration of a nyajsw extension. */
    public static Builder nyajsw(String template, KeyType type, KeyCategory category) {
        return new Builder(template, type, category, KeyOrigin.NYAJSW);
    }

    /** The key name or template as users see it, e.g. {@code wrapper.java.additional.<n>}. */
    public String name() {
        return pattern.template();
    }

    /** Whether {@code key} is this key or a member of this family of keys. */
    public boolean matches(String key) {
        return pattern.matches(key);
    }

    /**
     * The canonical value for an {@link KeyType#ENUM} spelling: a declared value or alias, compared
     * case-insensitively as YAJSW's enumerations are written in either case. Empty for other types
     * and for unknown spellings.
     */
    public Optional<String> canonicalValue(String spelling) {
        Objects.requireNonNull(spelling, "spelling");
        String upper = spelling.toUpperCase(Locale.ROOT);
        for (String value : values) {
            if (value.toUpperCase(Locale.ROOT).equals(upper)) {
                return Optional.of(value);
            }
        }
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            if (alias.getKey().toUpperCase(Locale.ROOT).equals(upper)) {
                return Optional.of(alias.getValue());
            }
        }
        return Optional.empty();
    }

    /** Whether a key name ends in {@code .password}, which is always treated as a secret (AGENTS.md §4.7). */
    static boolean isPasswordName(String key) {
        return key.toLowerCase(Locale.ROOT).endsWith(PASSWORD_SUFFIX);
    }

    private static void checkValues(String name, KeyType type, List<String> values, Map<String, String> aliases) {
        if (type != KeyType.ENUM) {
            if (!values.isEmpty() || !aliases.isEmpty()) {
                throw invalid(name, "only enumeration keys declare values and aliases");
            }
            return;
        }
        if (values.isEmpty()) {
            throw invalid(name, "an enumeration key needs its values");
        }
        List<String> seen = new ArrayList<>();
        for (String value : values) {
            String upper = value.toUpperCase(Locale.ROOT);
            if (value.isBlank() || seen.contains(upper)) {
                throw invalid(name, "enumeration values must be non-blank and unique ignoring case: " + values);
            }
            seen.add(upper);
        }
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            if (alias.getKey().isBlank()) {
                throw invalid(name, "aliases must be non-blank");
            }
            if (!values.contains(alias.getValue())) {
                throw invalid(name, "alias " + alias.getKey() + " maps to an undeclared value " + alias.getValue());
            }
            String upper = alias.getKey().toUpperCase(Locale.ROOT);
            if (seen.contains(upper)) {
                throw invalid(name, "alias " + alias.getKey() + " repeats a value or another alias");
            }
            seen.add(upper);
        }
    }

    private static void checkDefault(String name, KeyType type, KeyDefault defaultValue, List<String> values) {
        if (!(defaultValue instanceof KeyDefault.Constant constant)) {
            return;
        }
        String value = constant.value();
        boolean valid = switch (type) {
            case ENUM -> values.contains(value);
            case INTEGER, DURATION -> value.matches("-?[0-9]+");
            case BOOLEAN -> value.equals("true") || value.equals("false");
            case SIZE -> SIZE_VALUE.matcher(value).matches();
            case STRING, PATH, LIST -> !value.isEmpty();
        };
        if (!valid) {
            throw invalid(name, "default '" + value + "' is not a valid " + type.label());
        }
    }

    private static IllegalArgumentException invalid(String name, String reason) {
        return new IllegalArgumentException("invalid declaration of " + name + ": " + reason);
    }

    /**
     * Collects a declaration. Only the template, type, category, origin, status and description are
     * mandatory; the default is {@link KeyDefault#NONE}.
     */
    public static final class Builder {

        private final KeyPattern pattern;
        private final KeyType type;
        private final KeyCategory category;
        private final KeyOrigin origin;
        private KeyStatus status;
        private String phase = "";
        private KeyDefault defaultValue = KeyDefault.NONE;
        private boolean secret;
        private String description;
        private String notes = "";
        private final Set<Unverified> unverified = EnumSet.noneOf(Unverified.class);
        private final List<String> values = new ArrayList<>();
        private final Map<String, String> aliases = new LinkedHashMap<>();

        private Builder(String template, KeyType type, KeyCategory category, KeyOrigin origin) {
            this.pattern = KeyPattern.of(template);
            this.type = Objects.requireNonNull(type, "type");
            this.category = Objects.requireNonNull(category, "category");
            this.origin = origin;
        }

        /** A status without a phase: supported, ignored, or unsupported with no plan to change that. */
        public Builder status(KeyStatus status) {
            return status(status, "");
        }

        /** A status with the phase that implements (or would implement) the key. */
        public Builder status(KeyStatus status, String phase) {
            this.status = Objects.requireNonNull(status, "status");
            this.phase = Objects.requireNonNull(phase, "phase");
            return this;
        }

        public Builder description(String description) {
            this.description = Objects.requireNonNull(description, "description");
            return this;
        }

        public Builder defaultValue(String value) {
            this.defaultValue = KeyDefault.of(value);
            return this;
        }

        public Builder derivedDefault(String description) {
            this.defaultValue = KeyDefault.derived(description);
            return this;
        }

        public Builder secret() {
            this.secret = true;
            return this;
        }

        public Builder notes(String notes) {
            this.notes = Objects.requireNonNull(notes, "notes");
            return this;
        }

        /** Marks parts of a YAJSW declaration as still to be confirmed (R11). */
        public Builder toVerify(Unverified... parts) {
            unverified.addAll(List.of(parts));
            return this;
        }

        /** The canonical values of an enumeration key, in documentation order. */
        public Builder values(List<String> values) {
            this.values.addAll(values);
            return this;
        }

        public Builder alias(String alias, String canonical) {
            aliases.put(Objects.requireNonNull(alias, "alias"), Objects.requireNonNull(canonical, "canonical"));
            return this;
        }

        /**
         * @throws IllegalStateException    if the status or description was not given
         * @throws IllegalArgumentException if the declaration is inconsistent (see {@link KeySpec})
         */
        public KeySpec build() {
            if (status == null || description == null) {
                throw new IllegalStateException("declaration of " + pattern + " needs a status and a description");
            }
            return new KeySpec(pattern, type, defaultValue, origin, status, phase, category, secret, description,
                    notes, unverified, values, aliases);
        }
    }
}
