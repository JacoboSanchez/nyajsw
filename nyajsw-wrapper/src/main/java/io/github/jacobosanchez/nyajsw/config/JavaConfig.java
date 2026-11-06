package io.github.jacobosanchez.nyajsw.config;

import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.APP_ENV;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.APP_PARAMETER;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.IMAGE;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.JAVA_ADDITIONAL;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.JAVA_APP_JAR;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.JAVA_APP_MAINCLASS;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.JAVA_CLASSPATH;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.JAVA_COMMAND;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.JAVA_COMMAND_JAVAW;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.JAVA_INITMEMORY;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.JAVA_LIBRARY_PATH;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.JAVA_MAXMEMORY;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.TMP_PATH;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.WORKING_DIR;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The application process as configured (plan §4.5 typed views, §4.1). Paths are absolute. The
 * working directory resolves against the configuration file's directory (D13); everything the
 * application uses (jar, library path, image, a Java executable given with a directory) resolves
 * against the working directory, as the application itself sees it, and so do classpath entries
 * when they are expanded at launch (P2.3); they stay text here because they may hold wildcards. The
 * Java executable's default (the wrapper's own {@code java}/{@code javaw}) depends on the platform and
 * is resolved when the command line is built (P2.2); a bare name such as {@code java} is kept for a
 * {@code PATH} lookup.
 *
 * @param command        {@code wrapper.java.command}, if set: backslashes normalised, and resolved
 *                       against the working directory when it names a directory
 * @param javaw          {@code wrapper.java.command.javaw}
 * @param mainClass      {@code wrapper.java.app.mainclass}, if set
 * @param jar            {@code wrapper.java.app.jar}, if set
 * @param parameters     {@code wrapper.app.parameter.<n>} in index order
 * @param classpath      {@code wrapper.java.classpath.<n>} in index order, backslashes normalised
 * @param libraryPath    {@code wrapper.java.library.path.<n>} in index order, an entry that lists
 *                       several paths separated by {@code ;} (copied from <code>${path}</code>) split
 *                       into them
 * @param jvmOptions     {@code wrapper.java.additional.<n>} in index order
 * @param initMemoryMb   {@code wrapper.java.initmemory}, if set
 * @param maxMemoryMb    {@code wrapper.java.maxmemory}, if set
 * @param environment    {@code wrapper.app.env.<variable>} in file order, by variable name
 * @param workingDir     {@code wrapper.working.dir}, by default the configuration file's directory
 * @param tmpPath        {@code wrapper.tmp.path}, if set
 * @param image          {@code wrapper.image}, if set
 */
public record JavaConfig(Optional<String> command, boolean javaw, Optional<String> mainClass, Optional<Path> jar,
        List<String> parameters, List<String> classpath, List<Path> libraryPath, List<String> jvmOptions,
        Optional<Integer> initMemoryMb, Optional<Integer> maxMemoryMb, Map<String, String> environment,
        Path workingDir, Optional<Path> tmpPath, Optional<Path> image) {

    public JavaConfig {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(mainClass, "mainClass");
        Objects.requireNonNull(jar, "jar");
        parameters = List.copyOf(parameters);
        classpath = List.copyOf(classpath);
        libraryPath = List.copyOf(libraryPath);
        jvmOptions = List.copyOf(jvmOptions);
        Objects.requireNonNull(initMemoryMb, "initMemoryMb");
        Objects.requireNonNull(maxMemoryMb, "maxMemoryMb");
        environment = Collections.unmodifiableMap(new LinkedHashMap<>(environment));
        Objects.requireNonNull(workingDir, "workingDir");
        Objects.requireNonNull(tmpPath, "tmpPath");
        Objects.requireNonNull(image, "image");
    }

    /**
     * Hides values: free text such as parameters, commands or URLs can contain a secret copied in
     * with <code>${...}</code>. {@link ConfigDump} is the sanctioned way to show values.
     */
    @Override
    public String toString() {
        return "JavaConfig[values hidden; see ConfigDump]";
    }

    static JavaConfig from(ConfigValues values) {
        Path workingDir = values.path(WORKING_DIR).orElse(values.directory());
        return new JavaConfig(
                command(values, workingDir),
                values.required(values.bool(JAVA_COMMAND_JAVAW), JAVA_COMMAND_JAVAW),
                values.text(JAVA_APP_MAINCLASS),
                values.path(JAVA_APP_JAR, workingDir),
                values.familyValues(APP_PARAMETER),
                values.familyValues(JAVA_CLASSPATH).stream().map(ValueParser::normalisePath).toList(),
                values.familyPathLists(JAVA_LIBRARY_PATH, workingDir),
                values.familyValues(JAVA_ADDITIONAL),
                values.integer(JAVA_INITMEMORY),
                values.integer(JAVA_MAXMEMORY),
                environment(values),
                workingDir,
                values.path(TMP_PATH),
                values.path(IMAGE, workingDir));
    }

    /** A bare executable name stays for a PATH lookup; anything with a directory becomes a path. */
    private static Optional<String> command(ConfigValues values, Path workingDir) {
        Optional<String> text = values.text(JAVA_COMMAND).map(ValueParser::normalisePath);
        if (text.isEmpty() || (text.get().indexOf('/') < 0 && text.get().indexOf('\\') < 0)) {
            return text;
        }
        return values.path(JAVA_COMMAND, workingDir).map(Path::toString);
    }

    /** Variables by name; names differing only in case are kept but warned about (one variable on Windows). */
    private static Map<String, String> environment(ConfigValues values) {
        Map<String, String> environment = new LinkedHashMap<>();
        Map<String, KeyCollector.Member> byFoldedName = new LinkedHashMap<>();
        for (KeyCollector.Member member : values.family(APP_ENV)) {
            String name = member.lastPlaceholder();
            KeyCollector.Member earlier = byFoldedName.putIfAbsent(name.toUpperCase(Locale.ROOT), member);
            if (earlier != null) {
                values.warn(member.entry().location(), member.key() + " and " + earlier.key()
                        + " differ only in case; Windows treats them as one variable");
            }
            environment.put(name, member.value());
        }
        return environment;
    }
}
