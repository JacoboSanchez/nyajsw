package io.jsl.nyajsw.launch;

import io.jsl.nyajsw.config.SecretMasker;
import io.jsl.nyajsw.platform.HostOs;

import java.io.IOException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Turns the {@code wrapper.java.classpath.<n>} entries into the classpath of the application (plan
 * P2.3). Relative entries resolve against the working directory, and every result is absolute, so
 * the classpath means the same whatever directory the application later changes to.
 *
 * <ul>
 *   <li>An entry without wildcards is taken as it is (a jar or a directory); one that does not exist
 *       is kept, since the JVM ignores it, with a warning.</li>
 *   <li>In a segment, {@code *} matches any run of characters and {@code ?} one character, as in
 *       YAJSW ({@code lib/*}, {@code lib/*.jar}, <code>lib/&#42;/*.jar</code>); as there, {@code lib/*}
 *       means every file in {@code lib}, not only jars. A whole segment {@code **} matches any number
 *       of directories, a nyajsw extension: YAJSW's reference says recursive wildcards are not
 *       supported. Wildcards match regular files only; links are followed.</li>
 *   <li>The files one wildcard entry matches are added in the order of their path relative to the
 *       wildcard's base directory, with {@code /} between segments, case-insensitively first, so
 *       the result does not depend on the order the file system lists them in (risk R7). An entry
 *       that matches nothing gives a warning.</li>
 *   <li>A file that occurs twice (by its text; short names and links are not resolved) is kept at
 *       its first position.</li>
 *   <li>A directory that cannot be read is skipped with a warning; only a base directory that
 *       exists but cannot be read stops the launch.</li>
 * </ul>
 *
 * With Windows rules (on Windows) names match case-insensitively, a {@code \\?\} prefix is literal,
 * and drive-relative entries such as {@code C:lib} are skipped with a warning, since a service has
 * no current directory per drive. With either rules both {@code /} and {@code \} separate segments.
 * Warnings and errors show entries masked, since they are configuration text.
 */
public final class ClasspathExpander {

    private static final Pattern DRIVE = Pattern.compile("[A-Za-z]:");
    private static final Pattern DRIVE_RELATIVE = Pattern.compile("[A-Za-z]:(?![/\\\\]).*", Pattern.DOTALL);
    private static final List<String> LONG_PATH_PREFIXES = List.of("\\\\?\\", "//?/");

    private final boolean windows;

    /** @param windows whether Windows path rules apply: case-insensitive names, drive letters */
    public ClasspathExpander(boolean windows) {
        this.windows = windows;
    }

    /** An expander with the path rules of this host. */
    public static ClasspathExpander forThisHost() {
        return new ClasspathExpander(HostOs.isWindows());
    }

    /**
     * @param entries    the configured entries, in index order
     * @param workingDir the application's working directory
     * @param secrets    hides secrets in warnings and errors, since entries are configuration text
     * @throws LaunchException when the base directory of a wildcard exists but cannot be read
     */
    public Expanded expand(List<String> entries, Path workingDir, SecretMasker secrets) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(workingDir, "workingDir");
        Objects.requireNonNull(secrets, "secrets");
        Map<String, String> byIdentity = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        for (String entry : entries) {
            for (Path path : expandOne(entry, workingDir, secrets, warnings)) {
                String text = path.toString();
                byIdentity.putIfAbsent(windows ? text.toLowerCase(Locale.ROOT) : text, text);
            }
        }
        return new Expanded(List.copyOf(byIdentity.values()), warnings);
    }

    private List<Path> expandOne(String entry, Path workingDir, SecretMasker secrets, List<String> warnings) {
        String shown = secrets.maskText(entry);
        String prefix = windows
                ? LONG_PATH_PREFIXES.stream().filter(entry::startsWith).findFirst().orElse("")
                : "";
        String body = entry.substring(prefix.length());
        if (windows && prefix.isEmpty() && DRIVE_RELATIVE.matcher(entry).matches()) {
            warnings.add("classpath entry " + shown + " is relative to the current directory of drive "
                    + entry.substring(0, 2) + ", which a service does not have; skipped");
            return List.of();
        }
        List<String> segments = List.of(body.split("[/\\\\]", -1));
        int firstWildcard = -1;
        for (int i = 0; i < segments.size(); i++) {
            if (isWildcard(segments.get(i))) {
                firstWildcard = i;
                break;
            }
        }
        try {
            if (firstWildcard < 0) {
                Path path = workingDir.resolve(prefix + String.join("/", segments)).normalize();
                if (!Files.exists(path)) {
                    warnings.add("classpath entry " + shown + " does not exist");
                }
                return List.of(path);
            }
            String base = prefix + String.join("/", segments.subList(0, firstWildcard));
            if ((firstWildcard > 0 && segments.subList(0, firstWildcard).stream().allMatch(String::isEmpty))
                    || DRIVE.matcher(base).matches()) {
                base += "/"; // the root of the file system or of a drive, not the working directory
            }
            Path baseDir = workingDir.resolve(base).normalize();
            List<String> pattern = new ArrayList<>();
            for (String segment : segments.subList(firstWildcard, segments.size())) {
                if (segment.equals("..")) {
                    warnings.add("classpath entry " + shown + " has '..' after a wildcard, which nyajsw does not"
                            + " follow; skipped");
                    return List.of();
                }
                if (!segment.isEmpty() && !segment.equals(".")) {
                    pattern.add(segment);
                }
            }
            List<Path> matches = new Walk(baseDir, pattern, shown, secrets, warnings).run();
            if (matches.isEmpty()) {
                warnings.add("classpath entry " + shown + " matches no file");
            }
            return matches;
        } catch (InvalidPathException e) {
            warnings.add("classpath entry " + shown + " is not a valid path; skipped");
            return List.of();
        }
    }

    private static boolean isWildcard(String segment) {
        return segment.indexOf('*') >= 0 || segment.indexOf('?') >= 0;
    }

    /** One segment of a pattern as a regular expression over a name; {@code **} is handled by callers. */
    static String segmentRegex(String segment) {
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (char c : segment.toCharArray()) {
            if (c == '*' || c == '?') {
                if (!literal.isEmpty()) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(c == '*' ? "[^/]*" : "[^/]");
            } else {
                literal.append(c);
            }
        }
        if (!literal.isEmpty()) {
            regex.append(Pattern.quote(literal.toString()));
        }
        return regex.toString();
    }

    /** The pattern segments as a regular expression over a relative path with {@code /} separators. */
    static String regex(List<String> pattern) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.size(); i++) {
            String segment = pattern.get(i);
            boolean last = i == pattern.size() - 1;
            if (segment.equals("**")) {
                // Any number of directories, including none; at the end it also matches the files below.
                regex.append(last ? ".*" : "(?:[^/]+/)*");
                continue;
            }
            regex.append(segmentRegex(segment));
            if (!last) {
                regex.append('/');
            }
        }
        return regex.toString();
    }

    /** One wildcard entry's walk below its base directory. */
    private final class Walk extends SimpleFileVisitor<Path> {

        private final Path baseDir;
        private final List<String> pattern;
        private final List<Pattern> segments = new ArrayList<>();
        private final Pattern whole;
        private final String shown;
        private final SecretMasker secrets;
        private final List<String> warnings;
        /** Matches by sort key, so each relative path is computed once. */
        private final Map<String, Path> matches = new TreeMap<>(
                String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));

        Walk(Path baseDir, List<String> pattern, String shown, SecretMasker secrets, List<String> warnings) {
            this.baseDir = baseDir;
            this.pattern = pattern;
            int flags = windows ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
            for (String segment : pattern) {
                segments.add(segment.equals("**") ? null : Pattern.compile(segmentRegex(segment), flags));
            }
            this.whole = Pattern.compile(regex(pattern), flags);
            this.shown = shown;
            this.secrets = secrets;
            this.warnings = warnings;
        }

        List<Path> run() {
            if (!Files.isDirectory(baseDir) || pattern.isEmpty()) {
                return List.of();
            }
            try {
                Files.walkFileTree(baseDir, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, this);
            } catch (IOException e) {
                throw new LaunchException("cannot list the directory of classpath entry " + shown + ": "
                        + secrets.maskText(String.valueOf(e.getMessage())), e);
            }
            return List.copyOf(matches.values());
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
            return dir.equals(baseDir) || mayHoldMatches(parts(dir)) ? FileVisitResult.CONTINUE
                    : FileVisitResult.SKIP_SUBTREE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            String relative = String.join("/", parts(file));
            if (attributes.isRegularFile() && whole.matcher(relative).matches()) {
                matches.put(relative, file);
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
            if (file.equals(baseDir)) {
                throw e;
            }
            String why = e instanceof FileSystemLoopException ? "a link loops back" : "it cannot be read";
            warnings.add("classpath entry " + shown + ": " + secrets.maskText(file.toString())
                    + " is skipped because " + why);
            return FileVisitResult.CONTINUE;
        }

        /** Whether a directory with these relative segments can lead to a match, so the walk prunes the rest. */
        private boolean mayHoldMatches(List<String> parts) {
            for (int i = 0; i < parts.size(); i++) {
                if (segments.get(i) == null) {
                    return true; // ** from here on
                }
                if (i >= pattern.size() - 1) {
                    return false; // the last segment names files, so no deeper directory fits
                }
                if (!segments.get(i).matcher(parts.get(i)).matches()) {
                    return false;
                }
            }
            return true;
        }

        private List<String> parts(Path path) {
            List<String> parts = new ArrayList<>();
            for (Path part : baseDir.relativize(path)) {
                parts.add(part.toString());
            }
            return parts;
        }
    }

    /**
     * The outcome of {@link #expand}.
     *
     * @param entries  absolute classpath entries, in order, without duplicates
     * @param warnings entries that do not exist, match nothing or could not be read, for the caller to log
     */
    public record Expanded(List<String> entries, List<String> warnings) {

        public Expanded {
            entries = List.copyOf(entries);
            warnings = List.copyOf(warnings);
        }
    }
}
