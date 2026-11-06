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
 *   <li>{@code wrapper.java.additional.<n>}, empty ones (after unquoting) skipped with a warning;</li>
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
        Objects.requireNonNull(java, "java");
        Objects.requireNonNull(classpath, "classpath");
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
        for (String option : java.jvmOptions()) {
            String unquoted = unquote(option, JAVA_ADDITIONAL.name(), secrets);
            if (unquoted.isEmpty()) {
                // java.exe would take an empty argument for the main class.
                warnings.add("an empty " + JAVA_ADDITIONAL.name() + " is skipped");
            } else {
                arguments.add(unquoted);
            }
        }
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
        return new JavaCommand(arguments, warnings, secrets);
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
    static String unquote(String argument, String source, SecretMasker secrets) {
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
