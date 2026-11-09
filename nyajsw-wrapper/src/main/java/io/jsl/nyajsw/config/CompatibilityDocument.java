package io.jsl.nyajsw.config;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Renders {@code docs/COMPATIBILITY.md}, the configuration compatibility matrix, from the key
 * registry (plan §4.5, decision D8), so the document cannot drift from the code. A test compares the
 * committed document with {@link #render(KeyRegistry)} and rewrites it on request (AGENTS.md §3).
 *
 * <p>The output is deterministic: keys in registry order, grouped by category in order of first
 * appearance, LF line endings.
 */
public final class CompatibilityDocument {

    /** The command that regenerates the document, shown in it. */
    public static final String REGENERATE_COMMAND = "mvn -q -pl nyajsw-wrapper -am test -Dtest=CompatibilityDocTest"
            + " -Dsurefire.failIfNoSpecifiedTests=false -Dnyajsw.docs.update=true";

    private CompatibilityDocument() {
    }

    public static String render(KeyRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        StringBuilder out = new StringBuilder();
        out.append("# nyajsw configuration compatibility\n\n");
        out.append("<!-- Generated from the key registry (ConfigKeys). Do not edit by hand; regenerate with:\n");
        out.append("     ").append(REGENERATE_COMMAND).append(" -->\n\n");
        out.append("Every configuration key nyajsw knows, with how far it is supported. Keys come from YAJSW's\n");
        out.append("`wrapper.conf` unless marked as a nyajsw extension. A file may contain any of them: unknown\n");
        out.append("`wrapper.*` keys and keys that are planned, ignored or unsupported produce a warning naming\n");
        out.append("the key. Only two things stop the wrapper: a key marked *fatal* set to a non-empty value,\n");
        out.append("because ignoring it would silently change behaviour, and a value of a supported key that does\n");
        out.append("not fit its type. Keys outside `wrapper.*` are plain variables for `${...}`.\n\n");
        out.append("Status: **supported** takes effect · **planned** not implemented yet, no effect · **ignored**\n");
        out.append("accepted on purpose, no effect · **unsupported** no effect · **unsupported, fatal when\n");
        out.append("present** stops the wrapper when set. A phase in brackets refers to the\n");
        out.append("[implementation plan](IMPLEMENTATION_PLAN.md): the phase that implements a planned key, or\n");
        out.append("for an unsupported key the backlog (P9) where it may be added later. *Verify* marks parts of\n");
        out.append("a YAJSW declaration still to be confirmed against YAJSW itself.\n\n");
        appendSummary(out, registry.specs());
        Map<KeyCategory, List<KeySpec>> byCategory = registry.specs().stream()
                .collect(Collectors.groupingBy(KeySpec::category, LinkedHashMap::new, Collectors.toList()));
        byCategory.forEach((category, specs) -> appendCategory(out, category, specs));
        appendExtensions(out, registry.specs());
        return out.toString();
    }

    private static void appendSummary(StringBuilder out, List<KeySpec> specs) {
        Map<KeyStatus, Long> counts = new EnumMap<>(KeyStatus.class);
        specs.forEach(spec -> counts.merge(spec.status(), 1L, Long::sum));
        long extensions = specs.stream().filter(spec -> spec.origin() == KeyOrigin.NYAJSW).count();
        out.append("| Status | Keys |\n|---|---|\n");
        for (KeyStatus status : KeyStatus.values()) {
            out.append("| ").append(status.label()).append(" | ").append(counts.getOrDefault(status, 0L))
                    .append(" |\n");
        }
        out.append("| *of which nyajsw extensions* | ").append(extensions).append(" |\n");
        out.append("| **total** | ").append(specs.size()).append(" |\n\n");
    }

    private static void appendCategory(StringBuilder out, KeyCategory category, List<KeySpec> specs) {
        out.append("## ").append(category.title()).append("\n\n");
        out.append("| Key | Meaning | Type | Default | Status | Notes |\n");
        out.append("|---|---|---|---|---|---|\n");
        for (KeySpec spec : specs) {
            out.append("| ").append(code(spec.name()))
                    .append(" | ").append(prose(spec.description()))
                    .append(" | ").append(type(spec))
                    .append(" | ").append(defaultValue(spec.defaultValue()))
                    .append(" | ").append(status(spec))
                    .append(" | ").append(notes(spec))
                    .append(" |\n");
        }
        out.append('\n');
    }

    /** The one place listing every nyajsw extension; the document ends with it (single final newline). */
    private static void appendExtensions(StringBuilder out, List<KeySpec> specs) {
        out.append("## nyajsw extensions\n\n");
        out.append("Keys YAJSW does not know; they live in the same `wrapper.*` namespace:\n\n");
        specs.stream().filter(spec -> spec.origin() == KeyOrigin.NYAJSW)
                .forEach(spec -> out.append("- ").append(code(spec.name())).append('\n'));
    }

    private static String type(KeySpec spec) {
        if (spec.type() != KeyType.ENUM) {
            return spec.type().label();
        }
        String values = spec.values().stream().map(CompatibilityDocument::code).collect(Collectors.joining(", "));
        String aliases = spec.aliases().entrySet().stream()
                .map(alias -> code(alias.getKey()) + " = " + code(alias.getValue()))
                .collect(Collectors.joining(", "));
        return "one of " + values + (aliases.isEmpty() ? "" : "; alias " + aliases);
    }

    private static String defaultValue(KeyDefault value) {
        return switch (value) {
            case KeyDefault.None none -> "—";
            case KeyDefault.Constant constant -> code(constant.value());
            case KeyDefault.Derived derived -> prose(derived.description());
        };
    }

    private static String status(KeySpec spec) {
        String status = "**" + spec.status().label() + "**" + (spec.phase().isEmpty() ? "" : " (" + spec.phase() + ")");
        return spec.origin() == KeyOrigin.NYAJSW ? status + "<br>nyajsw extension" : status;
    }

    private static String notes(KeySpec spec) {
        StringBuilder notes = new StringBuilder(prose(spec.notes()));
        if (!spec.unverified().isEmpty()) {
            append(notes, "*Verify:* " + spec.unverified().stream().map(Unverified::label)
                    .collect(Collectors.joining(", ")));
        }
        if (spec.secret()) {
            append(notes, "Secret: never logged or shown.");
        }
        return notes.toString();
    }

    private static void append(StringBuilder notes, String text) {
        if (!notes.isEmpty()) {
            notes.append("<br>");
        }
        notes.append(text);
    }

    /** Inline code; a backtick would end the span, so registry text containing one is rejected. */
    private static String code(String text) {
        if (text.indexOf('`') >= 0) {
            throw new IllegalArgumentException("backtick in registry text: " + text);
        }
        return "`" + text.replace("|", "\\|") + "`";
    }

    /**
     * Text outside code: backslashes, table separators and angle brackets (e.g. {@code <n>}) escaped.
     * Line breaks cannot occur: {@link KeySpec} rejects them in prose.
     */
    private static String prose(String text) {
        return text.replace("\\", "\\\\").replace("|", "\\|").replace("<", "&lt;").replace(">", "&gt;");
    }
}
