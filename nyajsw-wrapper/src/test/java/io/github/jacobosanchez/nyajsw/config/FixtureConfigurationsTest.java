package io.github.jacobosanchez.nyajsw.config;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Every configuration under {@code src/test/resources/conf/fixtures/} must load (plan P1, "done
 * when"). Each fixture is a directory that mirrors an installation (the configuration where the
 * product keeps it, e.g. {@code conf/wrapper.conf} or {@code conf/app/service.conf}) with a
 * {@code fixture.properties} manifest:
 *
 * <ul>
 *   <li>{@code main}: the configuration file, relative to the fixture directory (required);</li>
 *   <li>{@code env.NAME}: an environment variable; {@code sys.name}: a wrapper JVM system property;</li>
 *   <li>{@code caseInsensitiveEnvironment}: {@code true} (default, as on Windows) or {@code false}.</li>
 * </ul>
 *
 * A directory without a manifest fails the test, so a fixture is never skipped silently. The
 * environment comes only from the manifest, so results do not depend on the machine.
 */
public class FixtureConfigurationsTest {

    private static final String MANIFEST = "fixture.properties";

    @DataProvider
    public Object[][] fixtures() throws IOException, URISyntaxException {
        try (Stream<Path> dirs = Files.list(fixturesDirectory())) {
            return dirs.filter(Files::isDirectory)
                    .sorted()
                    .map(dir -> new Object[] {dir.getFileName().toString()})
                    .toArray(Object[][]::new);
        }
    }

    @Test(dataProvider = "fixtures")
    public void fixtureLoadsWithoutErrorsOrUnknownKeys(String name) throws Exception {
        WrapperConfig config = load(name);

        for (ConfigWarning warning : config.warnings()) {
            assertFalse(warning.message().startsWith("unknown key"), name + ": " + warning);
            assertFalse(warning.message().startsWith("unresolved variable"), name + ": " + warning);
        }
    }

    @Test
    public void thereIsAtLeastTheWrittenFromScratchFixture() throws Exception {
        assertTrue(Files.isRegularFile(fixturesDirectory().resolve("yajsw-style").resolve(MANIFEST)));
    }

    @Test
    public void yajswStyleFixtureKeepsItsWindowsLineEndings() throws Exception {
        byte[] bytes = Files.readAllBytes(fixturesDirectory().resolve("yajsw-style/conf/wrapper.conf"));

        assertTrue(new String(bytes, StandardCharsets.UTF_8).contains("\r\n"), "CRLF lost (.gitattributes -text?)");
    }

    @Test
    public void yajswStyleFixtureIsTypedAsWritten() throws Exception {
        WrapperConfig config = load("yajsw-style");
        Path home = fixturesDirectory().resolve("yajsw-style");
        Path conf = home.resolve("conf");

        JavaConfig java = config.java();
        assertEquals(java.workingDir(), home);
        // Resolved like the code does on each OS: absolute on Windows, under the working directory on POSIX.
        assertEquals(java.command(), Optional.of(home.resolve("C:\\jdk8\\bin\\java").toString()));
        assertEquals(java.mainClass(), Optional.of("com.example.inventory.InventoryServer"));
        // ${wrapper.working.dir} expands to the value as written (".."), not to the resolved directory.
        assertEquals(java.classpath(), List.of("lib\\*.jar", "conf", "..\\plugins\\*"));
        assertEquals(java.jvmOptions(), List.of("-Xss512k", "-Dfile.encoding=UTF-8", "-Dinventory.mode=production",
                "-XX:+HeapDumpOnOutOfMemoryError", "-XX:HeapDumpPath=log",
                "-Dinventory.data=\"C:\\\\Program Files\\\\Inventory\\\\data\""));
        assertEquals(java.initMemoryMb(), Optional.of(128));
        assertEquals(java.maxMemoryMb(), Optional.of(512));
        // Text values keep backslashes as written (D7); only path keys and the account undo doubled
        // ones. YAJSW's Commons Configuration probably reads "\\\\" as one backslash here: settled in P1.9.
        assertEquals(java.parameters(), List.of("--config", "conf\\\\inventory.properties"));
        assertEquals(java.environment(), Map.of("INVENTORY_MODE", "production"));

        ServiceConfig service = config.service();
        assertEquals(service.name(), Optional.of("InventoryServer"));
        assertEquals(service.displayName(), Optional.of("Inventory Server"));
        assertEquals(service.description(), Optional.of("Keeps the inventory of the example warehouse"
                + " up to date and answers stock queries"));
        assertEquals(service.startType(), StartType.DELAYED_AUTO_START);
        assertEquals(service.dependencies(), List.of("Tcpip"));
        assertEquals(service.account(), Optional.of("CORP\\svc-inventory"));
        assertEquals(service.password().orElseThrow().reveal(), "Not-A-Real-Password-1");

        LifecycleConfig lifecycle = config.lifecycle();
        assertEquals(lifecycle.control(), ControlMode.TIGHT);
        assertEquals(lifecycle.exitAction(0), ExitAction.SHUTDOWN);
        assertEquals(lifecycle.exitAction(1), ExitAction.RESTART);
        assertEquals(lifecycle.restartDelay(), Duration.ofSeconds(5));
        assertEquals(lifecycle.maxFailedInvocations(), 3);
        assertEquals(lifecycle.startupTimeout(), Duration.ofSeconds(60));
        assertEquals(lifecycle.shutdownTimeout(), Duration.ofSeconds(45));
        assertEquals(lifecycle.pingInterval(), Optional.of(Duration.ofSeconds(30)));

        LogConfig log = config.log();
        assertEquals(log.logfile(), Optional.of(conf.resolve("log\\wrapper_YYYYMMDD.log")));
        assertEquals(log.maxSizeBytes(), Optional.of(10L * 1024 * 1024));
        assertEquals(log.maxFiles(), Optional.of(10));
        assertEquals(log.logfileLevel(), Optional.of(LogLevel.INFO));
    }

    @Test
    public void yajswStyleFixtureWarnsExactlyAboutWhatNyajswDoesNotDo() throws Exception {
        List<String> messages = load("yajsw-style").warnings().stream().map(ConfigWarning::message).toList();
        String notYet = " is not implemented yet in this version of nyajsw; it has no effect";

        // Nothing else: a new warning (duplicate key, continuation heuristic, encoding fallback, ...) fails.
        for (String message : messages) {
            boolean expected = message.contains(notYet)
                    || message.startsWith("wrapper.java.mainclass is ignored by nyajsw")
                    || message.startsWith("wrapper.ntservice.interactive is ignored by nyajsw")
                    || message.equals("wrapper.tray is not supported by nyajsw; it has no effect")
                    || message.equals("wrapper.tray.port is not supported by nyajsw; it has no effect")
                    || message.equals("wrapper.app.account is not supported by nyajsw; it is empty and has no effect")
                    || message.startsWith("optional include skipped, file not found: ");
            assertTrue(expected, "unexpected warning: " + message);
        }
        // Positive controls, so the filter above cannot go vacuous when wording changes.
        assertTrue(messages.contains("wrapper.java.additional.<n> (6 keys)" + notYet), messages.toString());
        assertTrue(messages.contains("optional include skipped, file not found: "
                + fixturesDirectory().resolve("yajsw-style/conf/local-overrides.conf")), messages.toString());
        assertTrue(messages.contains("wrapper.tray is not supported by nyajsw; it has no effect"), messages.toString());
    }

    @Test
    public void yajswStyleFixtureDumpHidesThePassword() throws Exception {
        Path file = mainFile("yajsw-style");
        LoadedConfig loaded = loader(manifest("yajsw-style")).load(file);

        String dump = ConfigDump.render(ConfigKeys.registry(), loaded);

        assertFalse(dump.contains("Not-A-Real-Password-1"), dump);
        assertTrue(dump.contains("wrapper.ntservice.password = ****"), dump);
    }

    private static WrapperConfig load(String name) throws IOException, URISyntaxException {
        return WrapperConfig.from(loader(manifest(name)).load(mainFile(name)));
    }

    private static Path mainFile(String name) throws IOException, URISyntaxException {
        String main = manifest(name).getProperty("main");
        if (main == null) {
            throw new AssertionError(name + "/" + MANIFEST + " does not name its main configuration file");
        }
        Path file = fixturesDirectory().resolve(name).resolve(main);
        if (!Files.isRegularFile(file)) {
            throw new AssertionError(name + "/" + MANIFEST + " names a missing file: " + main);
        }
        return file;
    }

    private static Properties manifest(String name) throws IOException, URISyntaxException {
        Path path = fixturesDirectory().resolve(name).resolve(MANIFEST);
        if (!Files.isRegularFile(path)) {
            throw new AssertionError("fixture " + name + " has no " + MANIFEST + "; see FixtureConfigurationsTest");
        }
        Properties manifest = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            manifest.load(reader);
        }
        return manifest;
    }

    private static ConfigLoader loader(Properties manifest) {
        Map<String, String> environment = new HashMap<>();
        Map<String, String> systemProperties = new HashMap<>();
        for (String key : manifest.stringPropertyNames()) {
            if (key.startsWith("env.")) {
                environment.put(key.substring(4), manifest.getProperty(key));
            } else if (key.startsWith("sys.")) {
                systemProperties.put(key.substring(4), manifest.getProperty(key));
            }
        }
        boolean caseInsensitive = Boolean.parseBoolean(manifest.getProperty("caseInsensitiveEnvironment", "true"));
        return new ConfigLoader(systemProperties, environment, caseInsensitive, ConfigKeys.registry());
    }

    private static Path fixturesDirectory() throws URISyntaxException {
        return Path.of(FixtureConfigurationsTest.class.getResource("/conf/fixtures").toURI());
    }
}
