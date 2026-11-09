package io.jsl.nyajsw.platform.windows;

import static io.jsl.nyajsw.config.ConfigKeys.NTSERVICE_ADDITIONAL;
import static io.jsl.nyajsw.config.ConfigKeys.NTSERVICE_JAVA_COMMAND;

import io.jsl.nyajsw.NyajswException;
import io.jsl.nyajsw.config.ConfigException;
import io.jsl.nyajsw.config.JavaConfig;
import io.jsl.nyajsw.config.WrapperConfig;
import io.jsl.nyajsw.launch.JavaCommandBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the command line the Service Control Manager runs for a service (its binary path, plan
 * §4.4, D12):
 *
 * <pre>
 * &lt;java.home&gt;\bin\javaw.exe &lt;wrapper.ntservice.additional.n …&gt; -Djna.tmpdir=&lt;wrapper.tmp.path&gt;
 *     -cp &lt;classpath&gt; io.jsl.nyajsw.cli.Main --service &lt;absolute wrapper.conf&gt;
 * </pre>
 *
 * <p>each argument quoted where needed ({@link WindowsArguments}).
 *
 * <p>The Java executable is the installing JVM's {@code javaw.exe}, since the wrapper needs Java 21,
 * unless {@code wrapper.ntservice.java.command} names another one. That one must be given with its
 * directory (a bare name would be looked up on the system {@code PATH} at every start) and exist,
 * and a Java older than 21 by its installation's {@code release} file is an error rather than a
 * silent fallback (D12); when the version cannot be told, a warning says so. The classpath is the
 * installing JVM's (callers may give another one, as the integration tests do, whose {@code
 * java.class.path} is a booter jar), every entry absolute: the service does not start in the
 * installing directory. The JVM options are unquoted like {@code wrapper.java.additional.<n>}
 * ({@link JavaCommandBuilder#unquote}); an empty one is skipped with a warning. {@code -Djna.tmpdir}
 * comes after them, so that it is the one in effect: JNA is loaded before the configuration is
 * read.
 *
 * <p>The Java launcher reads its command line in the system's ANSI code page, so an argument that
 * code page cannot hold (a path in another script) is an error: the service would get question
 * marks instead. Windows stores the binary path as an expandable string, so a {@code %NAME%} in it is
 * replaced at every start; that gives a warning. The binary path is readable by every local user
 * ({@code sc qc}): a secret copied into a JVM option with <code>${...}</code> is in plain text there.
 */
public final class ServiceCommandLine {

    /** The wrapper's entry point; a string, since {@code platform} does not depend on {@code cli}. */
    static final String MAIN_CLASS = "io.jsl.nyajsw.cli.Main";

    /** The switch that makes the entry point run as the service. */
    static final String SERVICE_SWITCH = "--service";

    static final int MINIMUM_JAVA = 21;

    private static final Pattern JAVA_VERSION = Pattern.compile("(?m)^JAVA_VERSION=\"(\\d{1,4})(?:\\.(\\d{1,4}))?(?!\\d)");
    private static final Pattern VARIABLE = Pattern.compile("%[^%]+%");

    private final Path javaHome;
    private final List<Path> classpath;
    private final Charset commandLineCharset;

    /**
     * @param javaHome           the {@code java.home} whose {@code javaw.exe} runs the service by default
     * @param classpath          the wrapper's classpath, in order; entries are made absolute
     * @param commandLineCharset the charset the Java launcher reads its command line in: the ANSI
     *                           code page ({@code native.encoding})
     * @throws NyajswException when the classpath is empty, as for a wrapper run from the module path
     */
    public ServiceCommandLine(Path javaHome, List<Path> classpath, Charset commandLineCharset) {
        this.javaHome = Objects.requireNonNull(javaHome, "javaHome").toAbsolutePath().normalize();
        this.classpath = classpath.stream().map(entry -> entry.toAbsolutePath().normalize()).toList();
        this.commandLineCharset = Objects.requireNonNull(commandLineCharset, "commandLineCharset");
        if (this.classpath.isEmpty()) {
            throw new NyajswException("cannot tell the wrapper's classpath for the service's command line:"
                    + " java.class.path is empty; run the wrapper with -cp or -jar");
        }
    }

    /** The command line of the JVM running this code: its {@code java.home}, classpath and code page. */
    public static ServiceCommandLine forThisJvm() {
        List<Path> classpath = Arrays.stream(System.getProperty("java.class.path", "")
                .split(Pattern.quote(File.pathSeparator))).filter(entry -> !entry.isEmpty()).map(Path::of).toList();
        return new ServiceCommandLine(Path.of(System.getProperty("java.home")), classpath, nativeCharset());
    }

    /** The ANSI code page the Java launcher reads its command line in, else the default charset. */
    static Charset nativeCharset() {
        try {
            return Charset.forName(System.getProperty("native.encoding"));
        } catch (IllegalArgumentException | NullPointerException e) { // not set, or not a charset this JVM has
            return Charset.defaultCharset();
        }
    }

    /**
     * The service's command line for {@code config}.
     *
     * @throws ConfigException when {@code wrapper.ntservice.java.command} is a bare name, names no file
     *                         or a Java older than 21, or when an argument does not fit the code page
     * @throws io.jsl.nyajsw.launch.LaunchException when a JVM option holds a double
     *                         quote that cannot be passed on
     */
    public ServiceCommand build(WrapperConfig config) {
        Objects.requireNonNull(config, "config");
        List<String> warnings = new ArrayList<>();
        List<String> arguments = new ArrayList<>();
        arguments.add(executable(config, warnings).toString());
        for (String option : config.service().wrapperJvmOptions()) {
            String unquoted = JavaCommandBuilder.unquote(option, NTSERVICE_ADDITIONAL.name(), config.secrets());
            if (unquoted.isEmpty()) {
                warnings.add("an empty " + NTSERVICE_ADDITIONAL.name() + " is skipped");
            } else {
                arguments.add(unquoted);
            }
        }
        config.java().tmpPath().ifPresent(tmp -> arguments.add("-Djna.tmpdir=" + tmp.toAbsolutePath().normalize()));
        arguments.add("-cp");
        arguments.add(String.join(";", classpath.stream().map(Path::toString).toList()));
        arguments.add(MAIN_CLASS);
        arguments.add(SERVICE_SWITCH);
        arguments.add(config.file().toAbsolutePath().normalize().toString());
        CharsetEncoder encoder = commandLineCharset.newEncoder();
        for (String argument : arguments) {
            String shown = config.secrets().maskText(argument);
            if (!encoder.canEncode(argument)) {
                throw new ConfigException("the service's command line cannot hold " + shown + ": the Java launcher"
                        + " reads it in the code page " + commandLineCharset.name() + ", which lacks some of its"
                        + " characters; use paths and options without them");
            }
            if (VARIABLE.matcher(argument).find()) {
                warnings.add("Windows replaces %NAME% in a service's command line with the variable's value at"
                        + " every start: " + shown);
            }
        }
        return new ServiceCommand(arguments, warnings);
    }

    private Path executable(WrapperConfig config, List<String> warnings) {
        Optional<String> configured = config.service().javaCommand();
        if (configured.isEmpty()) {
            return javaHome.resolve("bin").resolve("javaw.exe");
        }
        String shown = config.secrets().maskText(configured.get());
        if (JavaConfig.isBareName(configured.get())) {
            throw new ConfigException(NTSERVICE_JAVA_COMMAND.name() + "=" + shown + " has no directory: give the"
                    + " path of a Java " + MINIMUM_JAVA + " javaw.exe, since the service would look it up on the"
                    + " system PATH at every start");
        }
        Path command = Path.of(configured.get()).normalize();
        if (!Files.isRegularFile(command)) {
            throw new ConfigException(NTSERVICE_JAVA_COMMAND.name() + " names no file: " + shown);
        }
        OptionalInt version = javaVersion(command);
        if (version.isEmpty()) {
            warnings.add("cannot tell which Java " + NTSERVICE_JAVA_COMMAND.name() + " is (" + shown + "); the"
                    + " wrapper needs Java " + MINIMUM_JAVA + " or later");
        } else if (version.getAsInt() < MINIMUM_JAVA) {
            throw new ConfigException(NTSERVICE_JAVA_COMMAND.name() + " is a Java " + version.getAsInt()
                    + ", but the wrapper needs Java " + MINIMUM_JAVA + " or later: " + shown);
        }
        return command;
    }

    /**
     * The feature version of the Java installation {@code command} belongs to ({@code 1.8} counts as
     * 8), from the {@code release} file beside its {@code bin} directory, or above a JDK 8's {@code
     * jre}; links are followed first. Empty when that cannot be told.
     */
    static OptionalInt javaVersion(Path command) {
        Path real;
        try {
            real = command.toRealPath();
        } catch (IOException e) {
            real = command.toAbsolutePath();
        }
        Path bin = real.getParent();
        Path home = bin == null ? null : bin.getParent();
        if (home == null) {
            return OptionalInt.empty();
        }
        OptionalInt version = releaseVersion(home);
        Path jdk = home.getParent();
        if (version.isEmpty() && jdk != null && home.getFileName().toString().equalsIgnoreCase("jre")) {
            version = releaseVersion(jdk);
        }
        return version;
    }

    private static OptionalInt releaseVersion(Path home) {
        String release;
        try {
            release = Files.readString(home.resolve("release"));
        } catch (IOException | RuntimeException e) { // no such file, unreadable, or not text
            return OptionalInt.empty();
        }
        Matcher matcher = JAVA_VERSION.matcher(release);
        if (!matcher.find()) {
            return OptionalInt.empty();
        }
        int major = Integer.parseInt(matcher.group(1));
        if (major == 1 && matcher.group(2) != null) {
            major = Integer.parseInt(matcher.group(2));
        }
        return OptionalInt.of(major);
    }
}
