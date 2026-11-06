package io.github.jacobosanchez.nyajsw.launch;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import io.github.jacobosanchez.nyajsw.config.ConfigKeys;
import io.github.jacobosanchez.nyajsw.config.ConfigLoader;
import io.github.jacobosanchez.nyajsw.config.WrapperConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/** Golden tests: they compare argument arrays, never joined command lines (plan P2.2). */
public class JavaCommandBuilderTest {

    private static final Path JAVA_HOME = Path.of("jdk21");

    private Path dir;

    @BeforeMethod
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("nyajsw-command");
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws IOException {
        try (var paths = Files.walk(dir)) {
            for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.delete(path);
            }
        }
    }

    private WrapperConfig load(String... lines) throws IOException {
        Path file = Files.writeString(dir.resolve("wrapper.conf"), String.join("\n", lines) + "\n",
                StandardCharsets.UTF_8);
        return WrapperConfig.from(new ConfigLoader(Map.of(), Map.of(), false, ConfigKeys.registry()).load(file));
    }

    private static JavaCommand build(WrapperConfig config, boolean windows, String... classpath) {
        return new JavaCommandBuilder(JAVA_HOME, windows).build(config.java(), List.of(classpath), config.secrets());
    }

    /** The arguments without {@link JavaCommandBuilder#OUTPUT_ENCODING}, which its own test covers. */
    private static List<String> args(JavaCommand command) {
        List<String> arguments = new java.util.ArrayList<>(command.arguments());
        int at = arguments.indexOf(JavaCommandBuilder.OUTPUT_ENCODING.get(0));
        assertEquals(arguments.subList(at, at + 4), JavaCommandBuilder.OUTPUT_ENCODING);
        arguments.subList(at, at + 4).clear();
        return arguments;
    }

    @Test
    public void outputEncodingComesBeforeTheJvmOptionsAndAWrittenOneWins() throws IOException {
        JavaCommand plain = build(load("wrapper.java.app.mainclass=Main", "wrapper.java.maxmemory=64",
                "wrapper.java.additional.1=-Dx=1"), true);
        JavaCommand chosen = build(load("wrapper.java.app.mainclass=Main",
                "wrapper.java.additional.1=-Dsun.stdout.encoding=ISO-8859-1",
                "wrapper.java.additional.2=-Dstderr.encoding=windows-1252"), true);
        JavaCommand unknown = build(load("wrapper.java.app.mainclass=Main",
                "wrapper.java.additional.1=-Dstdout.encoding=no-such-charset"), true);

        assertEquals(plain.arguments().subList(1, 7), List.of("-Xmx64m", "-Dstdout.encoding=UTF-8",
                "-Dsun.stdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8", "-Dx=1"));
        assertEquals(plain.stdoutCharset(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(plain.stderrCharset(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(chosen.stdoutCharset(), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertEquals(chosen.stderrCharset().name(), "windows-1252");
        assertEquals(unknown.stdoutCharset(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(unknown.warnings().get(0).contains("no-such-charset"), unknown.warnings().toString());
    }

    @Test
    public void fullCommandLineInItsDocumentedOrder() throws IOException {
        WrapperConfig config = load(
                "wrapper.java.app.mainclass=com.example.Main",
                "wrapper.java.initmemory=128",
                "wrapper.java.maxmemory=512",
                "wrapper.java.additional.2=-Dtwo=2",
                "wrapper.java.additional.1=-Xss512k",
                "wrapper.java.library.path.1=native",
                "wrapper.java.library.path.2=C:/lib",
                "wrapper.app.parameter.1=--config",
                "wrapper.app.parameter.2=app.properties");

        JavaCommand command = build(config, true, "lib/a.jar", "conf");

        assertEquals(args(command), List.of(
                JAVA_HOME.resolve("bin").resolve("java.exe").toString(),
                "-Xms128m", "-Xmx512m",
                // C:/lib is absolute on Windows and relative to the working directory elsewhere.
                "-Djava.library.path=" + dir.resolve("native") + ";" + dir.resolve("C:/lib").normalize(),
                "-Xss512k", "-Dtwo=2",
                "-classpath", "lib/a.jar;conf",
                "com.example.Main",
                "--config", "app.properties"));
        assertTrue(command.warnings().isEmpty(), command.warnings().toString());
    }

    @Test
    public void posixUsesColonsAndJavaAndWarnsAboutJavaw() throws IOException {
        JavaCommand command = build(load("wrapper.java.app.mainclass=Main", "wrapper.java.command.javaw=true"),
                false, "a.jar", "b.jar");

        assertEquals(args(command), List.of(JAVA_HOME.resolve("bin").resolve("java").toString(),
                "-classpath", "a.jar:b.jar", "Main"));
        assertEquals(command.warnings(), List.of("wrapper.java.command.javaw only applies on Windows; java is used"));
    }

    @Test
    public void javawOnWindows() throws IOException {
        JavaCommand command = build(load("wrapper.java.app.mainclass=Main", "wrapper.java.command.javaw=true"), true);

        assertEquals(command.executable(), JAVA_HOME.resolve("bin").resolve("javaw.exe").toString());
    }

    @Test
    public void configuredJavaCommandWins() throws IOException {
        JavaCommand command = build(load("wrapper.java.app.mainclass=Main", "wrapper.java.command=java",
                "wrapper.java.command.javaw=true"), true);

        assertEquals(args(command), List.of("java", "Main"));
        assertEquals(command.warnings(), List.of(
                "wrapper.java.command.javaw is ignored because wrapper.java.command is set"));
    }

    @Test
    public void jarInsteadOfMainClassIgnoresMainClassAndClasspathWithWarnings() throws IOException {
        JavaCommand command = build(load("wrapper.java.app.jar=app.jar", "wrapper.java.app.mainclass=Main",
                "wrapper.app.parameter.1=x"), true, "lib/a.jar");

        assertEquals(args(command).subList(1, args(command).size()),
                List.of("-jar", dir.resolve("app.jar").toString(), "x"));
        assertEquals(command.warnings().size(), 2, command.warnings().toString());
        assertTrue(command.warnings().get(0).startsWith("wrapper.java.app.mainclass is ignored"));
        assertTrue(command.warnings().get(1).startsWith("wrapper.java.classpath.<n> is ignored"));
    }

    @Test
    public void firstParameterIsTheMainClassWhenNoneIsConfigured() throws IOException {
        // As YAJSW does, which also covers files converted from Tanuki's WrapperSimpleApp layout.
        JavaCommand command = build(load("wrapper.app.parameter.1=com.example.Main", "wrapper.app.parameter.2=arg"),
                true, "a.jar");

        assertEquals(args(command).subList(1, args(command).size()),
                List.of("-classpath", "a.jar", "com.example.Main", "arg"));
    }

    @Test
    public void nothingToStartIsAnError() throws IOException {
        WrapperConfig config = load("wrapper.java.additional.1=-Dx=1");

        LaunchException e = expectThrows(LaunchException.class, () -> build(config, true));

        assertTrue(e.getMessage().contains("wrapper.java.app.mainclass"), e.getMessage());
    }

    @Test
    public void emptyFirstParameterCannotBeTheMainClass() throws IOException {
        WrapperConfig config = load("wrapper.app.parameter.1=\"\"", "wrapper.app.parameter.2=arg");

        LaunchException e = expectThrows(LaunchException.class, () -> build(config, true));

        assertTrue(e.getMessage().contains("is empty"), e.getMessage());
    }

    @Test
    public void emptyJvmOptionIsSkippedWithAWarning() throws IOException {
        JavaCommand command = build(load("wrapper.java.app.mainclass=Main", "wrapper.java.additional.1=\"\"",
                "wrapper.app.parameter.1=\"\""), true);

        // An empty program argument is legitimate and kept.
        assertEquals(args(command).subList(1, args(command).size()), List.of("Main", ""));
        assertEquals(command.warnings(), List.of("an empty wrapper.java.additional.<n> is skipped"));
    }

    @Test
    public void heapSizesMustBePositive() throws IOException {
        WrapperConfig config = load("wrapper.java.app.mainclass=Main", "wrapper.java.maxmemory=0");

        LaunchException e = expectThrows(LaunchException.class, () -> build(config, true));

        assertTrue(e.getMessage().contains("wrapper.java.maxmemory"), e.getMessage());
    }

    @DataProvider
    public Object[][] quotedArguments() {
        return new Object[][] {
                {"-Dinventory.data=\"C:/Program Files/Inventory/data\"", "-Dinventory.data=C:/Program Files/Inventory/data"},
                {"\"a b\"", "a b"},
                {"-Dempty=\"\"", "-Dempty="},
                {"-XX:OnError=\"cmd /c echo\"", "-XX:OnError=cmd /c echo"},
                {"plain", "plain"},
        };
    }

    @Test(dataProvider = "quotedArguments")
    public void onePairOfQuotesAroundTheValueIsRemoved(String written, String passed) throws IOException {
        JavaCommand command = build(load("wrapper.java.app.mainclass=Main", "wrapper.java.additional.1=" + written),
                true);

        assertEquals(args(command).get(1), passed);
    }

    @DataProvider
    public Object[][] embeddedQuotes() {
        return new Object[][] {{"-Dx=a\"b"}, {"\"a\" \"b\""}, {"\""}, {"-Dx=\"a"}, {"a=\"b\"c"}};
    }

    @Test(dataProvider = "embeddedQuotes")
    public void otherQuotesAreAnError(String written) throws IOException {
        WrapperConfig config = load("wrapper.java.app.mainclass=Main", "wrapper.app.parameter.1=" + written);

        LaunchException e = expectThrows(LaunchException.class, () -> build(config, true));

        assertTrue(e.getMessage().startsWith("wrapper.app.parameter.<n> holds a double quote"), e.getMessage());
    }

    @Test
    public void secretsAreMaskedInTheLoggableFormAndInErrors() throws IOException {
        WrapperConfig config = load(
                "wrapper.ntservice.password=S3cr3t-pw",
                "wrapper.java.app.mainclass=Main",
                "wrapper.java.additional.1=-Ddb.password=${wrapper.ntservice.password}",
                "wrapper.app.parameter.1=--title=\"my app\"");

        JavaCommand command = build(config, true);

        assertTrue(args(command).contains("-Ddb.password=S3cr3t-pw"), "the real argument keeps the value");
        assertFalse(command.loggable().contains("S3cr3t-pw"), command.loggable());
        assertTrue(command.loggable().contains("-Ddb.password=****"), command.loggable());
        assertTrue(command.loggable().endsWith(" Main \"--title=my app\""), command.loggable());
        assertEquals(command.toString(), command.loggable());

        WrapperConfig quoted = load("wrapper.ntservice.password=S3cr3t-pw", "wrapper.java.app.mainclass=Main",
                "wrapper.java.additional.1=-Dx=${wrapper.ntservice.password}\"");
        LaunchException e = expectThrows(LaunchException.class, () -> build(quoted, true));
        assertFalse(e.getMessage().contains("S3cr3t-pw"), e.getMessage());
    }

    @Test
    public void aQuotedSecretIsMaskedAfterItsQuotesAreRemoved() throws IOException {
        WrapperConfig config = load("wrapper.ntservice.password=\"pa ss\"", "wrapper.java.app.mainclass=Main",
                "wrapper.java.additional.1=-Dpw=${wrapper.ntservice.password}");

        JavaCommand command = build(config, true);

        assertEquals(args(command).get(1), "-Dpw=pa ss");
        assertFalse(command.loggable().contains("pa ss"), command.loggable());
    }

    @Test
    public void yajswStyleFixtureBuildsTheExpectedCommandLine() throws IOException {
        Path home = Path.of("src/test/resources/conf/fixtures/yajsw-style").toAbsolutePath();
        Map<String, String> environment = Map.of("JAVA_HOME", "C:\\jdk8");
        WrapperConfig config = WrapperConfig.from(new ConfigLoader(Map.of(), environment, false, ConfigKeys.registry())
                .load(home.resolve("conf/wrapper.conf")));

        List<String> arguments = args(new JavaCommandBuilder(JAVA_HOME, true).build(config.java(),
                List.of("lib/a.jar", "conf"), config.secrets()));

        assertEquals(arguments.subList(1, 6), List.of("-Xms128m", "-Xmx512m", "-Xss512k", "-Dfile.encoding=UTF-8",
                "-Dinventory.mode=production"), arguments.toString());
        // The quotes around the value are gone; text values keep doubled backslashes for now (plan P1.9).
        assertTrue(arguments.contains("-Dinventory.data=C:\\\\Program Files\\\\Inventory\\\\data"),
                arguments.toString());
        assertEquals(arguments.subList(arguments.size() - 5, arguments.size()), List.of("-classpath",
                "lib/a.jar;conf", "com.example.inventory.InventoryServer", "--config",
                "conf\\\\inventory.properties"));
    }
}
