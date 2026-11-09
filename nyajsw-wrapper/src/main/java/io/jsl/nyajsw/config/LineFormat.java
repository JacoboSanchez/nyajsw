package io.jsl.nyajsw.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The layout of a log line as {@code wrapper.console.format} or {@code wrapper.logfile.format} give
 * it (plan §4.8): one field per letter, in the order written. The letters are those YAJSW documents,
 * in either case: {@code L} level, {@code P} prefix ({@code wrapper} or {@code jvm N}), {@code D}
 * thread, {@code T} time, {@code Z} time with milliseconds, {@code M} message. How the fields look
 * is the log's business ({@code log.LineFormatter}).
 *
 * <p>YAJSW lets a format end with the two characters {@code \n} or the four {@code \r\n} to choose
 * the line ending; otherwise lines end with the platform's. Spaces are ignored; any other character
 * is not a field and is skipped with a warning.
 *
 * @param fields        the fields of a line, at least one
 * @param lineSeparator what ends a line
 */
public record LineFormat(List<Field> fields, String lineSeparator) {

    /** A field of a line, with its letter. */
    public enum Field {
        LEVEL('L'), PREFIX('P'), THREAD('D'), TIME('T'), MILLIS('Z'), MESSAGE('M');

        private final char letter;

        Field(char letter) {
            this.letter = letter;
        }

        public char letter() {
            return letter;
        }

        private static Optional<Field> of(char letter) {
            char upper = Character.toUpperCase(letter);
            for (Field field : values()) {
                if (field.letter == upper) {
                    return Optional.of(field);
                }
            }
            return Optional.empty();
        }
    }

    private static final String CRLF = "\\r\\n";
    private static final String LF = "\\n";

    /** YAJSW's default console format, the registry's default of {@code wrapper.console.format}. */
    public static final LineFormat CONSOLE_DEFAULT = registryDefault(ConfigKeys.CONSOLE_FORMAT);

    /** YAJSW's default log file format, the registry's default of {@code wrapper.logfile.format}. */
    public static final LineFormat LOGFILE_DEFAULT = registryDefault(ConfigKeys.LOGFILE_FORMAT);

    private static LineFormat registryDefault(KeySpec spec) {
        return parse(((KeyDefault.Constant) spec.defaultValue()).value()).format().orElseThrow();
    }

    public LineFormat {
        fields = List.copyOf(fields);
        Objects.requireNonNull(lineSeparator, "lineSeparator");
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("a line format needs a field");
        }
    }

    /**
     * A format and what was wrong with its text.
     *
     * @param format   empty when the text has no field letter, so nothing is worth writing
     * @param problems what was skipped, each starting with a lower-case word to follow a key name
     */
    record Parsed(Optional<LineFormat> format, List<String> problems) {

        Parsed {
            Objects.requireNonNull(format, "format");
            problems = List.copyOf(problems);
        }
    }

    /** Reads the letters of {@code text}. */
    static Parsed parse(String text) {
        Objects.requireNonNull(text, "text");
        String letters = text;
        String separator = System.lineSeparator();
        if (letters.endsWith(CRLF)) {
            letters = letters.substring(0, letters.length() - CRLF.length());
            separator = "\r\n";
        } else if (letters.endsWith(LF)) {
            letters = letters.substring(0, letters.length() - LF.length());
            separator = "\n";
        }
        List<Field> fields = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (char letter : letters.toCharArray()) {
            Optional<Field> field = Field.of(letter);
            if (field.isPresent()) {
                fields.add(field.get());
            } else if (letter != ' ') {
                String shown = Character.isISOControl(letter) ? String.format("\\u%04x", (int) letter)
                        : String.valueOf(letter);
                problems.add("'" + shown + "' is not a format letter (L P D T Z M); skipped");
            }
        }
        if (fields.isEmpty()) {
            problems.add("no format letter, so nothing is written there");
            return new Parsed(Optional.empty(), problems);
        }
        return new Parsed(Optional.of(new LineFormat(fields, separator)), problems);
    }

    /** The letters of the fields, as a configuration file would give them (without the line ending). */
    @Override
    public String toString() {
        StringBuilder text = new StringBuilder();
        fields.forEach(field -> text.append(field.letter));
        return text.toString();
    }
}
