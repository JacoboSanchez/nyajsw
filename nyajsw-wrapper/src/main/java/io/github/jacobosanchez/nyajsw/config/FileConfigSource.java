package io.github.jacobosanchez.nyajsw.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
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
import java.util.Map;
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
 *   <li>A line ending in an odd number of backslashes continues on the next line: the last backslash
 *       is dropped and the next line is appended without its leading whitespace. Blank and comment
 *       lines inside a continued value are skipped, as YAJSW does, so one line of a multi-line value
 *       can be commented out. Because of this rule {@code C:\app\} must be written {@code C:\app\\} or
 *       {@code C:/app/}; a continuation line that looks like a new {@code wrapper.*} entry gets a
 *       warning. A value still continued at the end of the file is kept, with a warning.</li>
 *   <li>In values (and {@code include =} paths) a backslash before another backslash, a comma, a
 *       quote, a space or one of {@code # = : !} stands for that character, as in YAJSW, whose Commons
 *       Configuration parser treats the backslash as an escape character and the comma as a list
 *       delimiter ({@link #unescape}). Every other backslash is literal: like YAJSW, nyajsw keeps
 *       {@code \a}, but unlike it, it also keeps {@code \t}, {@code \n}, {@code \r}, {@code \f},
 *       {@code \b} and {@code \}{@code uXXXX}, which YAJSW reads as control characters and Unicode
 *       escapes, so that Windows paths such as {@code C:\temp} keep their meaning.</li>
 *   <li>{@code #include <path>}, {@code #include.required <path>} and {@code include = <path>} (key
 *       compared case-insensitively) insert another file at that point. A relative path is resolved
 *       against the directory of the including file; when nothing exists there but the path exists
 *       relative to the main configuration file, as YAJSW resolved it, that file is used with a
 *       warning. Surrounding double quotes are removed. {@code ${...}} variables in the path are
 *       resolved by the {@link Interpolator} with the layer order of the whole configuration: a key
 *       set by a higher layer ({@link Layers#above()}, e.g. a {@code -Dwrapper.*} system
 *       property), else the last entry for it read before the include line, else a lower layer
 *       ({@link Layers#below()}, the built-in defaults); then system properties and the
 *       environment.
 *       {@code #include.required} and {@code include =} are <em>required</em>: a missing file or an
 *       unresolved variable in the path is an error naming file and line. A plain {@code #include} is
 *       optional, as in the Tanuki format it comes from (and YAJSW treats it as a comment): the same
 *       problems, and also a Groovy expression or a reference cycle in its path, only produce a
 *       warning. An include path that reads a secret (a key the registry marks as secret, or a
 *       system property or environment variable of such a name) is refused, required or not, with a
 *       message that names neither the path nor the value. An include cycle is always an error. Other
 *       {@code #include.*} directives are ignored with a warning.</li>
 *   <li>With a declared encoding ({@code wrapper.conf.encoding}, see the four-argument constructor)
 *       every file is decoded strictly with it. Otherwise files are read as UTF-8; a leading byte
 *       order mark is skipped. A file without a byte order
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

    /** The characters a backslash escapes in YAJSW's Commons Configuration files, besides control codes. */
    private static final String ESCAPED = "\\,\"' #=:!";

    /**
     * The value with YAJSW's character escapes undone: a backslash before one of {@link #ESCAPED}
     * stands for that character, so {@code \\} is one backslash and {@code \,} a comma (the Denodo files
     * write {@code -Djava.locale.providers=COMPAT\,SPI} because the comma would otherwise split the
     * value into a list). Every other backslash is kept as written, including those YAJSW reads as
     * control-character or Unicode escapes. A comma without a backslash is also kept: nyajsw does not
     * split values into lists (D7).
     */
    static String unescape(String value) {
        if (value.indexOf('\\') < 0) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length() && ESCAPED.indexOf(value.charAt(i + 1)) >= 0) {
                out.append(value.charAt(++i));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private final Path file;
    private final Interpolator interpolator;
    private final Layers layers;
    /** The declared encoding, or {@code null} for detection. */
    private final Encoding encoding;

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
        this(file, interpolator, Layers.NONE);
    }

    /**
     * @param file         the configuration file; made absolute so messages and relative includes do
     *                     not depend on the current directory
     * @param interpolator resolves {@code ${...}} variables in include paths
     * @param layers       the entries of the layers around the file, for variables in include paths
     */
    public FileConfigSource(Path file, Interpolator interpolator, Layers layers) {
        this(file, interpolator, layers, null);
    }

    /**
     * @param file         the configuration file; made absolute so messages and relative includes do
     *                     not depend on the current directory
     * @param interpolator resolves {@code ${...}} variables in include paths
     * @param layers       the entries of the layers around the file, for variables in include paths
     * @param encoding     the declared encoding of the file and its includes
     *                     ({@code wrapper.conf.encoding}), or {@code null} for the default detection
     *                     (UTF-8, windows-1252 fallback). A declared encoding is decoded strictly: a
     *                     byte that does not fit is an error naming the line; a leading byte order
     *                     mark of that encoding is skipped; UTF-16 is accepted only when declared.
     */
    public FileConfigSource(Path file, Interpolator interpolator, Layers layers, Encoding encoding) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        this.interpolator = Objects.requireNonNull(interpolator, "interpolator");
        this.layers = Objects.requireNonNull(layers, "layers");
        this.encoding = encoding;
    }

    /**
     * A declared encoding and where it was declared, for messages.
     *
     * @param charset    the charset
     * @param declaredAt e.g. {@code system property wrapper.conf.encoding} or {@code wrapper.conf:3}
     */
    public record Encoding(Charset charset, String declaredAt) {

        public Encoding {
            Objects.requireNonNull(charset, "charset");
            Objects.requireNonNull(declaredAt, "declaredAt");
        }
    }

    /**
     * The configuration layers around the file, by key, so include paths resolve variables the way
     * the merged configuration will (plan §4.5, layering).
     *
     * @param above entries that override the file, e.g. {@code -Dwrapper.*} system properties
     * @param below entries the file overrides, e.g. built-in defaults
     */
    public record Layers(Map<String, ConfigEntry> above, Map<String, ConfigEntry> below) {

        /** No other layers: include paths see only the file's own entries. */
        public static final Layers NONE = new Layers(Map.of(), Map.of());

        public Layers {
            above = Map.copyOf(above);
            below = Map.copyOf(below);
        }
    }

    @Override
    public String description() {
        return file.toString();
    }

    @Override
    public SourceContent read() {
        ReadState state = new ReadState(file.getParent(), interpolator, layers, encoding, false);
        state.readFile(file, null);
        return new SourceContent(state.entries, state.warnings);
    }

    /**
     * Reads leniently to find out what the file declares, e.g. {@code wrapper.conf.encoding} before the
     * real read: includes that cannot be read (because their path only decodes correctly under the
     * declared encoding, say) are skipped silently, and warnings are dropped. Problems with the main
     * file itself still throw.
     */
    List<ConfigEntry> probe() {
        ReadState state = new ReadState(file.getParent(), interpolator, layers, encoding, true);
        state.readFile(file, null);
        return List.copyOf(state.entries);
    }

    /** State of one {@link #read()} call: results so far and the chain of files being included. */
    private static final class ReadState {

        private final Path mainDirectory;
        private final Interpolator interpolator;
        private final Layers layers;
        private final Encoding encoding;
        /** Lenient read for {@link FileConfigSource#probe()}: include problems are skipped. */
        private final boolean probe;
        private final List<ConfigEntry> entries = new ArrayList<>();
        private final List<ConfigWarning> warnings = new ArrayList<>();
        /** Real paths of the files being read, for cycle detection across symbolic links. */
        private final Deque<Path> realChain = new ArrayDeque<>();
        /** The same files as written in the configuration, for messages. */
        private final Deque<Path> shownChain = new ArrayDeque<>();

        ReadState(Path mainDirectory, Interpolator interpolator, Layers layers, Encoding encoding, boolean probe) {
            this.mainDirectory = mainDirectory;
            this.interpolator = interpolator;
            this.layers = layers;
            this.encoding = encoding;
            this.probe = probe;
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
                include(path, unescape(value), location, true);
                return;
            }
            entries.add(new ConfigEntry(key, unescape(value), location));
        }

        /**
         * @param required whether a file that cannot be included is an error ({@code include =},
         *                 {@code #include.required}) or only a warning (plain {@code #include})
         */
        private void include(Path includingFile, String rawPath, ConfigLocation location, boolean required) {
            if (!probe) {
                includeChecked(includingFile, rawPath, location, required);
                return;
            }
            try {
                includeChecked(includingFile, rawPath, location, required);
            } catch (ConfigException e) {
                // Probing only: the real read reports this problem, or finds the file under the right encoding.
            }
        }

        private void includeChecked(Path includingFile, String rawPath, ConfigLocation location, boolean required) {
            String pathText = unquote(trim(rawPath));
            if (pathText.isEmpty()) {
                skipOrFail(required, location, "include without a file name");
                return;
            }
            if (pathText.contains("${")) {
                Interpolator.Resolution resolution;
                try {
                    resolution = interpolator.resolve(pathText, location, this::layeredEntry);
                } catch (ConfigException e) {
                    if (required) {
                        throw e;
                    }
                    // A plain #include only warns, whatever the problem (YAJSW reads it as a comment).
                    warnings.add(new ConfigWarning(location, "optional include skipped, " + withoutLocation(e)));
                    return;
                }
                warnings.addAll(resolution.warnings());
                if (resolution.usesSecret()) {
                    // The path would carry a secret into messages and entry locations (AGENTS.md §4.7).
                    skipOrFail(required, location, "include path refers to a secret key, which is not allowed");
                    return;
                }
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

        /** The entry {@code key} has at this point, in layer order (see the class description). */
        private Optional<ConfigEntry> layeredEntry(String key) {
            ConfigEntry above = layers.above().get(key);
            if (above != null) {
                return Optional.of(above);
            }
            return lastEntry(key).or(() -> Optional.ofNullable(layers.below().get(key)));
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
            if (encoding != null) {
                return decodeDeclared(bytes, path);
            }
            if (startsWith(bytes, 0xFF, 0xFE) || startsWith(bytes, 0xFE, 0xFF)) {
                throw new ConfigException(fileLocation, "file is UTF-16 encoded; save it as UTF-8, or start the"
                        + " wrapper with -D" + ConfigKeys.CONF_ENCODING.name() + "=UTF-16");
            }
            for (byte b : bytes) {
                if (b == 0) {
                    throw new ConfigException(fileLocation,
                            "file contains NUL bytes (UTF-16 without byte order mark?); save it as UTF-8, or"
                                    + " start the wrapper with -D" + ConfigKeys.CONF_ENCODING.name()
                                    + "=UTF-16LE");
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

        /**
         * Strict decoding with the declared charset. A byte that does not fit is an error at its line
         * (nyajsw never drops input silently, D7; YAJSW's reader probably replaced it). A leading
         * byte order mark of the charset is skipped; a UTF-8 one under another charset is an error,
         * since it would otherwise corrupt the first key without any warning.
         */
        private String decodeDeclared(byte[] bytes, Path path) {
            Charset charset = encoding.charset();
            String declared = charset.name() + ", the encoding set by " + encoding.declaredAt();
            boolean utf8 = charset.equals(StandardCharsets.UTF_8);
            if (!utf8 && startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
                throw new ConfigException(ConfigLocation.of(path.toString()),
                        "file starts with a UTF-8 byte order mark but is to be read as " + declared);
            }
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            ByteBuffer in = ByteBuffer.wrap(bytes);
            CharBuffer out = CharBuffer.allocate((int) (bytes.length * (double) decoder.maxCharsPerByte()) + 16);
            CoderResult result = decoder.decode(in, out, true);
            if (!result.isError()) {
                result = decoder.flush(out);
            }
            out.flip();
            if (result.isError()) {
                long line = out.chars().filter(c -> c == '\n').count() + 1;
                throw new ConfigException(new ConfigLocation(path.toString(), (int) Math.min(line, Integer.MAX_VALUE)),
                        "byte that is not valid " + declared);
            }
            String text = out.toString();
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
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
    static String trim(String text) {
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
        return trim(ValueParser.unquote(text));
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
