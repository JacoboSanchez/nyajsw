package io.github.jacobosanchez.nyajsw.launch;

import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.APP_PARAMETER;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.JAVA_ADDITIONAL;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.JAVA_APP_JAR;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.JAVA_APP_MAINCLASS;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.JAVA_CLASSPATH;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.JAVA_COMMAND;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.JAVA_COMMAND_JAVAW;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.JAVA_INITMEMORY;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.JAVA_MAXMEMORY;

import io.github.jacobosanchez.nyajsw.config.JavaConfig;
import io.github.jacobosanchez.nyajsw.config.SecretMasker;
import io.github.jacobosanchez.nyajsw.platform.HostOs;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds the application's command line from {@link JavaConfig} (plan P2.2), in this order:
 *
 * <ol>
 *   <li>the Java executable: {@code wrapper.java.command}, else {@code java} (or {@code javaw} with
 *       {@code wrapper.java.command.javaw}, Windows only) of the wrapper's own {@code java.home};</li>
 *   <li>{@code -Xms}/{@code -Xmx} from {@code wrapper.java.initmemory}/{@code maxmemory} in MB, and
 *       {@code -Djava.library.path} from {@code wrapper.java.library.path.<n>}, before the JVM options:
 *       the JVM takes the last value, so an option written there wins, as YAJSW documents for
 *       {@code -Xmx};</li>
 *   <li>the options the wrapper adds itself, such as {@code -Xrs} in service mode;</li>
 *   <li>{@code wrapper.java.additional.<n>}, empty ones (after unquoting) skipped with a warning;</li>
 *   <li>the output encoding, after the JVM options so that it is the value in effect: the charset of
 *       standard output and error, UTF-8 ({@link #OUTPUT_ENCODING}) unless a JVM option sets one
 *       (the last of {@code -D<stream>.encoding} and {@code -Dsun.<stream>.encoding} written wins),
 *       given under both names, since Java 8 to 17 read {@code sun.std*.encoding} and 18 and later
 *       {@code std*.encoding}; so the wrapper decodes exactly what the application writes, whatever
 *       its version and the platform's code page;</li>
 *   <li>{@code -classpath} and the main class, or {@code -jar} and {@code wrapper.java.app.jar};
 *       without either, the first {@code wrapper.app.parameter.<n>} is the main class, as YAJSW
 *       does;</li>
 *   <li>the remaining {@code wrapper.app.parameter.<n>}.</li>
 * </ol>
 *
 * <p>Quoting (D19, risk R12): arguments are passed as an array. On POSIX each one reaches the
 * application unchanged, quotes included; on Windows {@code ProcessBuilder} wraps an argument that
 * contains blanks in quotes, so {@code -Dx="a b"} would become {@code "-Dx="a b""} and break the
 * command line. One pair of double quotes around the whole argument, or around everything after its
 * first {@code =}, is therefore removed, as a shell would. Any other double quote is an error: Windows
 * passes it on unescaped, which breaks the command line, and dropping it would change the value.
 *
 * <p>The classpath arrives already expanded ({@code ClasspathExpander}, P2.3).
 */
public final class JavaCommandBuilder {

    /** The output-encoding options when the JVM options choose none (see the class comment). */
    public static final List<String> OUTPUT_ENCODING = outputEncoding(StandardCharsets.UTF_8, StandardCharsets.UTF_8);

    private final Path javaHome;
    private final boolean windows;

    /**
     * @param javaHome the {@code java.home} whose executable is the default
     * @param windows  whether the command is for Windows ({@code java.exe}, {@code ;} separators)
     */
    public JavaCommandBuilder(Path javaHome, boolean windows) {
        this.javaHome = Objects.requireNonNull(javaHome, "javaHome");
        this.windows = windows;
    }

    /** A builder whose default executable is the one of the JVM running the wrapper. */
    public static JavaCommandBuilder forThisJvm() {
        return new JavaCommandBuilder(Path.of(System.getProperty("java.home")), HostOs.isWindows());
    }

    /**
     * @param java      the application as configured
     * @param classpath the expanded classpath entries, in order
     * @param secrets   hides the configuration's secrets in messages and in the loggable form
     * @throws LaunchException when there is nothing to start, a heap size is not positive, or an
     *                         argument holds a double quote that cannot be passed on safely
     */
    public JavaCommand build(JavaConfig java, List<String> classpath, SecretMasker secrets) {
        return build(java, classpath, List.of(), secrets);
    }

    /**
     * As {@link #build(JavaConfig, List, SecretMasker)}, with {@code wrapperOptions}, the JVM options
     * the wrapper itself adds (such as {@code -Xrs} in service mode), after {@code -Djava.library.path}
     * and before {@code wrapper.java.additional.<n>}.
     */
    public JavaCommand build(JavaConfig java, List<String> classpath, List<String> wrapperOptions, SecretMasker secrets) {
        Objects.requireNonNull(java, "java");
        Objects.requireNonNull(classpath, "classpath");
        Objects.requireNonNull(wrapperOptions, "wrapperOptions");
        Objects.requireNonNull(secrets, "secrets");
        List<String> arguments = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        arguments.add(executable(java, warnings));
        java.initMemoryMb().ifPresent(mb -> arguments.add("-Xms" + positive(mb, JAVA_INITMEMORY.name()) + "m"));
        java.maxMemoryMb().ifPresent(mb -> arguments.add("-Xmx" + positive(mb, JAVA_MAXMEMORY.name()) + "m"));
        if (!java.libraryPath().isEmpty()) {
            arguments.add("-Djava.library.path=" + String.join(separator(),
                    java.libraryPath().stream().map(Path::toString).toList()));
        }
        arguments.addAll(wrapperOptions);
        List<String> options = new ArrayList<>();
        for (String option : java.jvmOptions()) {
            String unquoted = unquote(option, JAVA_ADDITIONAL.name(), secrets);
            if (unquoted.isEmpty()) {
                // java.exe would take an empty argument for the main class.
                warnings.add("an empty " + JAVA_ADDITIONAL.name() + " is skipped");
            } else {
                options.add(unquoted);
            }
        }
        arguments.addAll(options);
        Charset stdout = outputCharset(options, "stdout", warnings, secrets);
        Charset stderr = outputCharset(options, "stderr", warnings, secrets);
        arguments.addAll(outputEncoding(stdout, stderr));
        List<String> parameters = new ArrayList<>();
        for (String parameter : java.parameters()) {
            parameters.add(unquote(parameter, APP_PARAMETER.name(), secrets));
        }
        Optional<Path> jar = java.jar();
        if (jar.isPresent()) {
            if (java.mainClass().isPresent()) {
                warnings.add(JAVA_APP_MAINCLASS.name() + " is ignored because " + JAVA_APP_JAR.name() + " is set");
            }
            if (!classpath.isEmpty()) {
                warnings.add(JAVA_CLASSPATH.name() + " is ignored because " + JAVA_APP_JAR.name() + " is set;"
                        + " java -jar takes the classpath from the jar's manifest");
            }
            arguments.add("-jar");
            arguments.add(jar.get().toString());
        } else {
            String mainClass = java.mainClass().orElseGet(() -> firstParameterAsMainClass(parameters));
            if (!classpath.isEmpty()) {
                arguments.add("-classpath");
                arguments.add(String.join(separator(), classpath));
            }
            arguments.add(mainClass);
        }
        arguments.addAll(parameters);
        return new JavaCommand(arguments, warnings, secrets, stdout, stderr);
    }

    /** Both property names for each stream, so that a JVM of any version reads the same charset. */
    private static List<String> outputEncoding(Charset stdout, Charset stderr) {
        return List.of("-Dstdout.encoding=" + stdout.name(), "-Dsun.stdout.encoding=" + stdout.name(),
                "-Dstderr.encoding=" + stderr.name(), "-Dsun.stderr.encoding=" + stderr.name());
    }

    /**
     * The charset the application is to write {@code stream} in: the last {@code -D<stream>.encoding}
     * or {@code -Dsun.<stream>.encoding} among the JVM options, else UTF-8. One that this JVM does not
     * know gives UTF-8 and a warning that shows the value masked, since it may come from a secret.
     */
    private static Charset outputCharset(List<String> options, String stream, List<String> warnings,
            SecretMasker secrets) {
        String chosen = null;
        for (String option : options) {
            for (String property : List.of("-D" + stream + ".encoding=", "-Dsun." + stream + ".encoding=")) {
                if (option.startsWith(property)) {
                    chosen = option.substring(property.length());
                }
            }
        }
        if (chosen == null) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(chosen);
        } catch (IllegalArgumentException e) {
            warnings.add("the application's " + stream + " encoding '" + secrets.maskText(chosen) + "' is not a"
                    + " charset this JVM knows; UTF-8 is used instead");
            return StandardCharsets.UTF_8;
        }
    }

    private String executable(JavaConfig java, List<String> warnings) {
        if (java.command().isPresent()) {
            if (java.javaw()) {
                warnings.add(JAVA_COMMAND_JAVAW.name() + " is ignored because " + JAVA_COMMAND.name() + " is set");
            }
            return java.command().get();
        }
        if (java.javaw() && !windows) {
            warnings.add(JAVA_COMMAND_JAVAW.name() + " only applies on Windows; java is used");
        }
        String name = windows ? (java.javaw() ? "javaw.exe" : "java.exe") : "java";
        return javaHome.resolve("bin").resolve(name).toString();
    }

    private String separator() {
        return windows ? ";" : ":";
    }

    /** YAJSW starts the first program argument as the main class when none is configured. */
    private static String firstParameterAsMainClass(List<String> parameters) {
        if (parameters.isEmpty()) {
            throw new LaunchException("nothing to start: set " + JAVA_APP_MAINCLASS.name() + " or "
                    + JAVA_APP_JAR.name());
        }
        String mainClass = parameters.remove(0);
        if (mainClass.isEmpty()) {
            throw new LaunchException("the first " + APP_PARAMETER.name() + " stands for the main class, since "
                    + JAVA_APP_MAINCLASS.name() + " is not set, but it is empty");
        }
        return mainClass;
    }

    private static int positive(int megabytes, String key) {
        if (megabytes <= 0) {
            throw new LaunchException(key + " must be a positive number of megabytes, not " + megabytes);
        }
        return megabytes;
    }

    /**
     * {@code argument} without one pair of double quotes around all of it or around everything after
     * its first {@code =}.
     *
     * @throws LaunchException for any other double quote; the message shows the argument masked
     */
    public static String unquote(String argument, String source, SecretMasker secrets) {
        int first = argument.indexOf('"');
        if (first < 0) {
            return argument;
        }
        int last = argument.length() - 1;
        boolean opensValue = first == 0 || first == argument.indexOf('=') + 1;
        boolean closesAtEnd = last > first && argument.charAt(last) == '"'
                && argument.indexOf('"', first + 1) == last;
        if (opensValue && closesAtEnd) {
            return argument.substring(0, first) + argument.substring(first + 1, last);
        }
        throw new LaunchException(source + " holds a double quote that cannot be passed on safely: "
                + secrets.maskText(argument) + "; quote the whole value (-Dname=\"a b\") or none of it");
    }
}
