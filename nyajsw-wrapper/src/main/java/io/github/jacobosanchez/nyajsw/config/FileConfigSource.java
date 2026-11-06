package io.github.jacobosanchez.nyajsw.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reads a {@code wrapper.conf} file and the files it includes, following the YAJSW file format
 * (plan §4.5, decision D7, which also lists where this deliberately differs from YAJSW):
 *
 * <ul>
 *   <li>One {@code key=value} entry per logical line, split at the first {@code =}; key and value are
 *       trimmed. Lines without {@code =} or with an empty key are skipped with a warning.</li>
 *   <li>Blank lines and lines whose first non-blank character is {@code #} or {@code !} are
 *       comments.</li>
 *   <li>Backslashes are literal, except that a line ending in an odd number of backslashes continues
 *       on the next line: the last backslash is dropped and the next line is appended without its
 *       leading whitespace. Blank and comment lines inside a continued value are skipped, as YAJSW
 *       does, so one line of a multi-line value can be commented out. Because of this rule
 *       {@code C:\app\} must be written {@code C:\app\\} or {@code C:/app/}; a continuation line that
 *       looks like a new {@code wrapper.*} entry gets a warning. Doubled backslashes are left as they
 *       are here and normalised by the typed configuration layer. A value still continued at the end
 *       of the file is kept, with a warning.</li>
 *   <li>{@code #include <path>}, {@code #include.required <path>} and {@code include = <path>} (key
 *       compared case-insensitively) insert another file at that point. A relative path is resolved
 *       against the directory of the including file; when nothing exists there but the path exists
 *       relative to the main configuration file, as YAJSW resolved it, that file is used with a
 *       warning. Surrounding double quotes are removed. {@code ${...}} variables in the path are
 *       resolved by the {@link Interpolator}, against the entries read before the include line (the
 *       last one wins for a repeated key), the system properties and the environment.
 *       {@code #include.required} and {@code include =} are <em>required</em>: a missing file or an
 *       unresolved variable in the path is an error naming file and line. A plain {@code #include} is
 *       optional, as in the Tanuki format it comes from (and YAJSW treats it as a comment): the same
 *       problems, and also a Groovy expression or a reference cycle in its path, only produce a
 *       warning. An include cycle is always an error. Other
 *       {@code #include.*} directives are ignored with a warning.</li>
 *   <li>Files are read as UTF-8; a leading byte order mark is skipped. A file without a byte order
 *       mark that is not valid UTF-8 is read as windows-1252, the usual encoding of files edited
 *       with Notepad on Western-European Windows, with a warning. UTF-16 files (byte order mark
 *       {@code FF FE}/{@code FE FF}, or NUL bytes) are rejected with a hint to save them as
 *       UTF-8.</li>
 *   <li>Line endings may be {@code \r\n}, {@code \n} or {@code \r}. Trimming also removes control
 *       characters such as a trailing DOS end-of-file marker ({@code Ctrl+Z}).</li>
 *   <li>Paths are normalised lexically ({@code a/../b} is {@code b}) before they are opened, which
 *       matches how Windows resolves them.</li>
 * </ul>
 */
public final class FileConfigSource implements ConfigSource {

    private static final String HASH_INCLUDE = "#include";
    private static final String HASH_INCLUDE_REQUIRED = "#include.required";
    private static final String INCLUDE_KEY = "include";
    /** An unsupported {@code #include.*} directive name, cut before anything that could be a value. */
    private static final Pattern OTHER_HASH_INCLUDE = Pattern.compile("^#include\\.[A-Za-z0-9_.]*");
    private static final Charset FALLBACK_CHARSET = Charset.forName("windows-1252");
    /** A continuation line that starts like this was probably meant to be an entry of its own. */
    private static final Pattern LOOKS_LIKE_WRAPPER_ENTRY = Pattern.compile("^wrapper\\.[^\\s=]*\\s*=.*");

    private final Path file;
    private final Interpolator interpolator;

    /**
     * Reads {@code file}, resolving include paths against this process's system properties and
     * environment.
     *
     * @param file the configuration file; made absolute so messages and relative includes do not
     *             depend on the current directory
     */
    public FileConfigSource(Path file) {
        this(file, Interpolator.forCurrentProcess());
    }

    /**
     * @param file         the configuration file; made absolute so messages and relative includes do
     *                     not depend on the current directory
     * @param interpolator resolves {@code ${...}} variables in include paths
     */
    public FileConfigSource(Path file, Interpolator interpolator) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        this.interpolator = Objects.requireNonNull(interpolator, "interpolator");
    }

    @Override
    public String description() {
        return file.toString();
    }

    @Override
    public SourceContent read() {
        ReadState state = new ReadState(file.getParent(), interpolator);
        state.readFile(file, null);
        return new SourceContent(state.entries, state.warnings);
    }

    /** State of one {@link #read()} call: results so far and the chain of files being included. */
    private static final class ReadState {

        private final Path mainDirectory;
        private final Interpolator interpolator;
        private final List<ConfigEntry> entries = new ArrayList<>();
        private final List<ConfigWarning> warnings = new ArrayList<>();
        /** Real paths of the files being read, for cycle detection across symbolic links. */
        private final Deque<Path> realChain = new ArrayDeque<>();
        /** The same files as written in the configuration, for messages. */
        private final Deque<Path> shownChain = new ArrayDeque<>();

        ReadState(Path mainDirectory, Interpolator interpolator) {
            this.mainDirectory = mainDirectory;
            this.interpolator = interpolator;
        }

        /**
         * @param path       the file to read
         * @param includedAt the include line that named the file, or {@code null} for the main file;
         *                   problems opening the file are reported there
         */
        void readFile(Path path, ConfigLocation includedAt) {
            ConfigLocation openLocation = includedAt != null ? includedAt : ConfigLocation.of(path.toString());
            String problem = openProblem(path);
            if (problem != null) {
                // The main file's location already names it; an include line does not.
                throw new ConfigException(openLocation, includedAt != null
                        ? "included " + problem + ": " + path
                        : "configuration " + problem);
            }
            Path realPath;
            try {
                realPath = path.toRealPath();
            } catch (IOException e) {
                throw new ConfigException(openLocation, "cannot read " + path + ": " + e.getMessage(), e);
            }
            if (realChain.contains(realPath)) {
                String chain = shownChain.stream().map(Path::toString).collect(Collectors.joining(" -> "));
                throw new ConfigException(openLocation, "include cycle: " + chain + " -> " + path);
            }
            List<String> lines = decode(path, openLocation).lines().toList();
            realChain.addLast(realPath);
            shownChain.addLast(path);
            try {
                for (int index = 0; index < lines.size(); index++) {
                    index = readLogicalLine(path, lines, index);
                }
            } finally {
                realChain.removeLast();
                shownChain.removeLast();
            }
        }

        /** Handles the logical line starting at {@code index}; returns the index of its last line. */
        private int readLogicalLine(Path path, List<String> lines, int index) {
            ConfigLocation location = new ConfigLocation(path.toString(), index + 1);
            String line = trim(lines.get(index));
            if (line.isEmpty()) {
                return index;
            }
            if (isComment(line)) {
                readCommentLine(path, line, location);
                return index;
            }
            StringBuilder logical = new StringBuilder();
            while (endsWithContinuation(line)) {
                logical.append(line, 0, line.length() - 1);
                int next = nextContentLine(path, lines, index + 1);
                if (next >= lines.size()) {
                    warnings.add(new ConfigWarning(location,
                            "value continued with a trailing backslash at the end of the file; kept as it is"));
                    readEntry(path, logical.toString(), location);
                    return lines.size() - 1;
                }
                index = next;
                line = trim(lines.get(index));
                if (LOOKS_LIKE_WRAPPER_ENTRY.matcher(line).matches()) {
                    warnings.add(new ConfigWarning(new ConfigLocation(path.toString(), index + 1),
                            "line is joined to the value of line " + location.line() + ", which ends in a single"
                                    + " backslash; end a directory with \\\\ or / to keep this line separate"));
                }
            }
            logical.append(line);
            readEntry(path, logical.toString(), location);
            return index;
        }

        /** First index at or after {@code from} that is not blank or a comment, skipping those lines. */
        private int nextContentLine(Path path, List<String> lines, int from) {
            int index = from;
            while (index < lines.size()) {
                String line = trim(lines.get(index));
                if (!line.isEmpty() && !isComment(line)) {
                    break;
                }
                if (includeDirective(line) != null) {
                    warnings.add(new ConfigWarning(new ConfigLocation(path.toString(), index + 1),
                            "include ignored: it is inside a value continued with a trailing backslash"));
                }
                index++;
            }
            return index;
        }

        private void readCommentLine(Path path, String line, ConfigLocation location) {
            String directive = includeDirective(line);
            if (directive != null) {
                include(path, line.substring(directive.length()), location, directive.equals(HASH_INCLUDE_REQUIRED));
                return;
            }
            Matcher other = OTHER_HASH_INCLUDE.matcher(line);
            if (other.find()) {
                warnings.add(new ConfigWarning(location, "directive ignored, not supported: " + other.group()));
            }
        }

        private void readEntry(Path path, String line, ConfigLocation location) {
            int separator = line.indexOf('=');
            if (separator < 0) {
                // The line is not echoed: it may hold a secret written with another separator.
                warnings.add(new ConfigWarning(location, "line ignored: no '=' between key and value"));
                return;
            }
            String key = trim(line.substring(0, separator));
            String value = trim(line.substring(separator + 1));
            if (key.isEmpty()) {
                warnings.add(new ConfigWarning(location, "line ignored: empty key before '='"));
                return;
            }
            if (key.toLowerCase(Locale.ROOT).equals(INCLUDE_KEY)) {
                include(path, value, location, true);
                return;
            }
            entries.add(new ConfigEntry(key, value, location));
        }

        /**
         * @param required whether a file that cannot be included is an error ({@code include =},
         *                 {@code #include.required}) or only a warning (plain {@code #include})
         */
        private void include(Path includingFile, String rawPath, ConfigLocation location, boolean required) {
            String pathText = unquote(trim(rawPath));
            if (pathText.isEmpty()) {
                skipOrFail(required, location, "include without a file name");
                return;
            }
            if (pathText.contains("${")) {
                Interpolator.Resolution resolution;
                try {
                    resolution = interpolator.resolve(pathText, location, this::lastEntry);
                } catch (ConfigException e) {
                    if (required) {
                        throw e;
                    }
                    // A plain #include only warns, whatever the problem (YAJSW reads it as a comment).
                    warnings.add(new ConfigWarning(location, "optional include skipped, " + withoutLocation(e)));
                    return;
                }
                warnings.addAll(resolution.warnings());
                if (!resolution.complete()) {
                    // Not naming the variables: some may come from the values of other (secret) keys.
                    skipOrFail(required, location, "unresolved variable in include path " + pathText);
                    return;
                }
                pathText = unquote(trim(resolution.value()));
                if (pathText.isEmpty()) {
                    skipOrFail(required, location, "include path is empty after resolving variables");
                    return;
                }
            }
            Path relative;
            try {
                relative = Path.of(pathText);
            } catch (InvalidPathException e) {
                skipOrFail(required, location, "invalid include path: " + pathText + " (" + e.getReason() + ")");
                return;
            }
            Path target = includingFile.resolveSibling(relative).normalize();
            if (!relative.isAbsolute() && !Files.exists(target)) {
                Path fromMain = mainDirectory.resolve(relative).normalize();
                if (Files.isRegularFile(fromMain)) {
                    warnings.add(new ConfigWarning(location, "include resolved against the directory of the main"
                            + " configuration file, not of the including file: " + fromMain));
                    target = fromMain;
                }
            }
            String problem = openProblem(target);
            if (problem != null && !required) {
                warnings.add(new ConfigWarning(location, "optional include skipped, " + problem + ": " + target));
                return;
            }
            readFile(target, location);
        }

        /** The last entry read so far for {@code key}: later definitions override earlier ones. */
        private Optional<ConfigEntry> lastEntry(String key) {
            for (int i = entries.size() - 1; i >= 0; i--) {
                if (entries.get(i).key().equals(key)) {
                    return Optional.of(entries.get(i));
                }
            }
            return Optional.empty();
        }

        private static String withoutLocation(ConfigException e) {
            String prefix = e.location().map(where -> where + ": ").orElse("");
            String message = e.getMessage();
            return message.startsWith(prefix) ? message.substring(prefix.length()) : message;
        }

        private void skipOrFail(boolean required, ConfigLocation location, String message) {
            if (required) {
                throw new ConfigException(location, message);
            }
            warnings.add(new ConfigWarning(location, "optional include skipped, " + message));
        }

        private String decode(Path path, ConfigLocation openLocation) {
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(path);
            } catch (IOException e) {
                throw new ConfigException(openLocation, "cannot read " + path + ": " + e.getMessage(), e);
            }
            ConfigLocation fileLocation = ConfigLocation.of(path.toString());
            if (startsWith(bytes, 0xFF, 0xFE) || startsWith(bytes, 0xFE, 0xFF)) {
                throw new ConfigException(fileLocation, "file is UTF-16 encoded; save it as UTF-8");
            }
            for (byte b : bytes) {
                if (b == 0) {
                    throw new ConfigException(fileLocation,
                            "file contains NUL bytes (UTF-16 without byte order mark?); save it as UTF-8");
                }
            }
            boolean bom = startsWith(bytes, 0xEF, 0xBB, 0xBF);
            int offset = bom ? 3 : 0;
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                        .toString();
            } catch (CharacterCodingException e) {
                if (bom) {
                    throw new ConfigException(fileLocation,
                            "file starts with a UTF-8 byte order mark but is not valid UTF-8", e);
                }
                warnings.add(new ConfigWarning(fileLocation, "file is not valid UTF-8; read as "
                        + FALLBACK_CHARSET.name() + ". Save it as UTF-8 to remove this warning"));
                return new String(bytes, FALLBACK_CHARSET);
            }
        }
    }

    private static boolean isComment(String trimmedLine) {
        return trimmedLine.startsWith("#") || trimmedLine.startsWith("!");
    }

    /**
     * The include directive the line starts with ({@code #include} or {@code #include.required}
     * followed by whitespace or the end of the line), or {@code null}.
     */
    private static String includeDirective(String line) {
        for (String directive : List.of(HASH_INCLUDE_REQUIRED, HASH_INCLUDE)) {
            if (line.startsWith(directive)
                    && (line.length() == directive.length() || isBlank(line.charAt(directive.length())))) {
                return directive;
            }
        }
        return null;
    }

    /** Why the path cannot be opened as a configuration file ("file not found", ...), or {@code null}. */
    private static String openProblem(Path path) {
        if (!Files.exists(path)) {
            return "file not found";
        }
        if (!Files.isRegularFile(path)) {
            return "file is not a regular file";
        }
        return null;
    }

    private static boolean endsWithContinuation(String line) {
        int backslashes = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    /** Removes whitespace and control characters at both ends. */
    private static String trim(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isBlank(text.charAt(start))) {
            start++;
        }
        while (end > start && isBlank(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean isBlank(char c) {
        return c <= ' ' || Character.isWhitespace(c);
    }

    private static String unquote(String text) {
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            return trim(text.substring(1, text.length() - 1));
        }
        return text;
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
