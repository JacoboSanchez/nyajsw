package io.github.jacobosanchez.nyajsw.config;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class ConfigLoaderTest {

    private Path dir;

    @BeforeMethod
    public void createDir() throws IOException {
        dir = Files.createTempDirectory("nyajsw-loader-test");
    }

    @AfterMethod(alwaysRun = true)
    public void deleteDir() throws IOException {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    @Test
    public void defaultsApplyUnlessAHigherLayerSetsTheKey() throws IOException {
        Path file = write("wrapper.conf", "wrapper.startup.timeout=60\nwrapper.shutdown.timeout=45\n");

        LoadedConfig config = loader(Map.of("wrapper.shutdown.timeout", "90"), Map.of()).load(file);

        assertEquals(value(config, "wrapper.startup.timeout"), "60");
        assertEquals(config.layer("wrapper.startup.timeout"), Optional.of(ConfigLayer.FILE));
        assertEquals(value(config, "wrapper.shutdown.timeout"), "90");
        assertEquals(config.layer("wrapper.shutdown.timeout"), Optional.of(ConfigLayer.SYSTEM_PROPERTY));
        assertEquals(value(config, "wrapper.max_failed_invocations"), constant(ConfigKeys.MAX_FAILED_INVOCATIONS));
        assertEquals(config.layer("wrapper.max_failed_invocations"), Optional.of(ConfigLayer.DEFAULT));
        assertEquals(config.get("wrapper.max_failed_invocations").orElseThrow().location(),
                ConfigLocation.of("built-in default"));
        // Template keys and derived defaults have no default entry.
        assertEquals(config.get("wrapper.stop.strategy"), Optional.empty());
        assertEquals(config.get("wrapper.on_exit.default").map(ConfigEntry::value),
                Optional.of(constant(ConfigKeys.ON_EXIT_DEFAULT)));
        // JVM properties that are no configuration keys do not become entries.
        assertEquals(config.get("java.home"), Optional.empty());
    }

    @Test
    public void keySetTwiceInTheFileTakesTheLastValueAtItsPositionWithWarning() throws IOException {
        Path common = write("common.conf", "wrapper.app.env.A=common\nwrapper.app.env.B=b\n");
        Path file = write("wrapper.conf", "#include common.conf\nwrapper.app.env.C=c\nwrapper.app.env.A=main\n");

        LoadedConfig config = loader(Map.of(), Map.of()).load(file);

        List<String> fileKeys = config.entries().stream()
                .filter(entry -> config.layer(entry.key()).orElseThrow() == ConfigLayer.FILE)
                .map(ConfigEntry::key).toList();
        assertEquals(fileKeys, List.of("wrapper.app.env.B", "wrapper.app.env.C", "wrapper.app.env.A"));
        assertEquals(value(config, "wrapper.app.env.A"), "main");
        assertEquals(config.warnings().size(), 1);
        ConfigWarning warning = config.warnings().get(0);
        assertEquals(warning.location(), new ConfigLocation(file.toString(), 3));
        assertEquals(warning.message(), "wrapper.app.env.A is set again; this value replaces the one at "
                + new ConfigLocation(common.toString(), 1));
    }

    @Test
    public void orderIsFileThenNewSystemPropertiesThenDefaults() throws IOException {
        Path file = write("wrapper.conf", "wrapper.app.env.B=b\nwrapper.app.env.A=a\n");

        LoadedConfig config = loader(Map.of(
                "wrapper.app.env.B", "override",
                "wrapper.app.env.Z", "z",
                "wrapper.app.env.Y", "y"), Map.of()).load(file);

        List<String> keys = config.entries().stream().map(ConfigEntry::key).toList();
        assertEquals(keys.subList(0, 4),
                List.of("wrapper.app.env.B", "wrapper.app.env.A", "wrapper.app.env.Y", "wrapper.app.env.Z"));
        assertEquals(value(config, "wrapper.app.env.B"), "override");
        for (String key : keys.subList(4, keys.size())) {
            assertEquals(config.layer(key), Optional.of(ConfigLayer.DEFAULT), key);
        }
    }

    @Test
    public void variablesResolveAcrossLayers() throws IOException {
        Path file = write("wrapper.conf", "app_home=C:/app\n"
                + "wrapper.java.classpath.1=${app_home}/lib/*.jar\n"
                + "wrapper.app.parameter.1=--timeout=${wrapper.startup.timeout}\n"
                + "wrapper.working.dir=${app_home}/work\n");

        LoadedConfig config = loader(Map.of("wrapper.tmp.path", "${wrapper.working.dir}/tmp"), Map.of()).load(file);

        assertEquals(value(config, "wrapper.java.classpath.1"), "C:/app/lib/*.jar");
        assertEquals(value(config, "wrapper.app.parameter.1"), "--timeout=" + constant(ConfigKeys.STARTUP_TIMEOUT));
        assertEquals(value(config, "wrapper.tmp.path"), "C:/app/work/tmp");
        assertTrue(config.warnings().isEmpty(), config.warnings().toString());
    }

    @Test
    public void variablesSeeTheOverridingSystemProperty() throws IOException {
        Path file = write("wrapper.conf", "wrapper.working.dir=C:/file\nwrapper.app.parameter.1=${wrapper.working.dir}\n");

        LoadedConfig config = loader(Map.of("wrapper.working.dir", "C:/override"), Map.of()).load(file);

        assertEquals(value(config, "wrapper.app.parameter.1"), "C:/override");
    }

    @Test
    public void anySystemPropertyOverridesAKeyTheFileSets() throws IOException {
        Path file = write("wrapper.conf", "app_home=C:/app\nwrapper.java.classpath.1=${app_home}/lib\n");

        LoadedConfig config = loader(Map.of("app_home", "D:/new", "unrelated", "x"), Map.of()).load(file);

        assertEquals(value(config, "wrapper.java.classpath.1"), "D:/new/lib");
        assertEquals(config.layer("app_home"), Optional.of(ConfigLayer.SYSTEM_PROPERTY));
        assertEquals(config.get("unrelated"), Optional.empty());
    }

    @Test
    public void includePathFollowsTheSameLayerOrder() throws IOException {
        write("b/x.conf", "in_b_x=1\n");
        write("b/y.conf", "in_b_y=1\n");
        String defaultHost = constant(ConfigKeys.READINESS_TCP_HOST);
        write(defaultHost + "/z.conf", "in_default=1\n");
        Path file = write("wrapper.conf", "wrapper.working.dir=a\n"
                + "include = ${wrapper.working.dir}/y.conf\n"
                + "conf_dir=a\n"
                + "include = ${conf_dir}/x.conf\n"
                + "include = ${wrapper.readiness.tcp.host}/z.conf\n");

        // Both system properties override the file's keys, so both includes read from b/, exactly as
        // every other ${wrapper.working.dir} and ${conf_dir} resolves. The third include sees the
        // built-in default of wrapper.readiness.tcp.host.
        LoadedConfig config = loader(Map.of("wrapper.working.dir", "b", "conf_dir", "b"), Map.of()).load(file);

        assertTrue(config.get("in_b_y").isPresent());
        assertTrue(config.get("in_b_x").isPresent());
        assertTrue(config.get("in_default").isPresent());
        assertTrue(config.warnings().isEmpty(), config.warnings().toString());
    }

    @Test
    public void repeatedKeyWarningMentionsAnOverridingSystemProperty() throws IOException {
        Path file = write("wrapper.conf", "wrapper.working.dir=a\nwrapper.working.dir=b\n");

        LoadedConfig config = loader(Map.of("wrapper.working.dir", "c"), Map.of()).load(file);

        assertEquals(value(config, "wrapper.working.dir"), "c");
        assertTrue(config.warnings().get(0).message().endsWith(", and a system property overrides both"),
                config.warnings().toString());
    }

    @Test
    public void fileThatDeclaresItsEncodingIsReadAgainWithIt() throws IOException {
        Path file = dir.resolve("wrapper.conf");
        Files.write(file, "wrapper.ntservice.displayname=Caf\u00e9\nwrapper.conf.encoding=ISO-8859-1\n"
                .getBytes(StandardCharsets.ISO_8859_1));

        LoadedConfig config = loader(Map.of(), Map.of()).load(file);

        assertEquals(value(config, "wrapper.ntservice.displayname"), "Caf\u00e9");
        assertTrue(config.warnings().isEmpty(), config.warnings().toString());
    }

    @Test
    public void systemPropertyEncodingWinsAndAllowsUtf16() throws IOException {
        Path file = dir.resolve("wrapper.conf");
        Files.write(file, "wrapper.conf.encoding=ISO-8859-1\nk=v\n".getBytes(StandardCharsets.UTF_16LE));

        LoadedConfig config = loader(Map.of("wrapper.conf.encoding", "UTF-16LE"), Map.of()).load(file);

        assertEquals(value(config, "k"), "v");
    }

    @Test
    public void unknownEncodingIsAnErrorAtItsLine() throws IOException {
        Path file = write("wrapper.conf", "a=1\nwrapper.conf.encoding=no-such-charset\n");

        ConfigException e = expectThrows(ConfigException.class, () -> loader(Map.of(), Map.of()).load(file));

        assertEquals(e.location().orElseThrow(), new ConfigLocation(file.toString(), 2));
        assertTrue(e.getMessage().contains("unknown encoding 'no-such-charset'"), e.getMessage());
    }

    @Test
    public void emptyEncodingKeepsTheDefaultDetection() throws IOException {
        Path file = dir.resolve("wrapper.conf");
        Files.write(file, "wrapper.conf.encoding=\nk=caf\u00e9\n".getBytes(StandardCharsets.ISO_8859_1));

        LoadedConfig config = loader(Map.of(), Map.of()).load(file);

        assertEquals(value(config, "k"), "caf\u00e9");
        assertTrue(config.warnings().get(0).message().contains("not valid UTF-8; read as windows-1252"),
                config.warnings().toString());
    }

    @Test
    public void includePathThatOnlyDecodesUnderTheDeclaredEncodingIsFound() throws IOException {
        java.nio.charset.Charset cyrillic = java.nio.charset.Charset.forName("windows-1251");
        Files.write(cyrillicFile(), "k=\u0434\u0430\n".getBytes(cyrillic));
        Path file = dir.resolve("wrapper.conf");
        Files.write(file, ("wrapper.conf.encoding=windows-1251\ninclude = \u043d\u0430\u0441\u0442.conf\n"
                + "#include \u043d\u0430\u0441\u0442.conf\n").getBytes(cyrillic));

        LoadedConfig config = loader(Map.of(), Map.of()).load(file);

        assertEquals(value(config, "k"), "\u0434\u0430");
        assertEquals(config.warnings().stream().filter(w -> w.message().contains("set again")).count(), 1L,
                config.warnings().toString());
    }

    @Test
    public void declarationInAnIncludeAppliesAndTheLastDeclarationWins() throws IOException {
        Files.write(dir.resolve("enc.conf"), "wrapper.conf.encoding=UTF-8\nwrapper.conf.encoding=ISO-8859-1\n"
                .getBytes(StandardCharsets.US_ASCII));
        Path file = dir.resolve("wrapper.conf");
        Files.write(file, "include = enc.conf\nk=caf\u00e9\n".getBytes(StandardCharsets.ISO_8859_1));

        LoadedConfig config = loader(Map.of(), Map.of()).load(file);

        assertEquals(value(config, "k"), "caf\u00e9");
        assertTrue(config.warnings().stream().noneMatch(w -> w.message().contains("not valid UTF-8")),
                config.warnings().toString());
    }

    @Test
    public void systemPropertyBeatsAConflictingFileDeclaration() throws IOException {
        Path file = dir.resolve("wrapper.conf");
        Files.write(file, "wrapper.conf.encoding=UTF-8\nk=caf\u00e9\n".getBytes(StandardCharsets.ISO_8859_1));

        LoadedConfig config = loader(Map.of("wrapper.conf.encoding", "ISO-8859-1"), Map.of()).load(file);

        assertEquals(value(config, "k"), "caf\u00e9");
        assertEquals(value(config, "wrapper.conf.encoding"), "ISO-8859-1");
    }

    @Test
    public void emptySystemPropertyForcesTheDefaultDetection() throws IOException {
        Path file = dir.resolve("wrapper.conf");
        Files.write(file, "wrapper.conf.encoding=UTF-8\nk=caf\u00e9\n".getBytes(StandardCharsets.ISO_8859_1));

        LoadedConfig config = loader(Map.of("wrapper.conf.encoding", ""), Map.of()).load(file);

        assertEquals(value(config, "k"), "caf\u00e9");
        assertTrue(config.warnings().get(0).message().contains("read as windows-1252"), config.warnings().toString());
    }

    @Test
    public void encodingThatIsNotAsciiCompatibleCannotBeDeclaredInTheFile() throws IOException {
        for (String name : List.of("UTF-16LE", "IBM037")) {
            Path file = write("wrapper.conf", "wrapper.conf.encoding=" + name + "\nk=v\n");

            ConfigException e = expectThrows(ConfigException.class, () -> loader(Map.of(), Map.of()).load(file));

            assertTrue(e.getMessage().contains("cannot be declared inside the configuration file"), e.getMessage());
            assertTrue(e.getMessage().contains("-Dwrapper.conf.encoding=" + name), e.getMessage());
        }
    }

    @Test
    public void declarationsThatDisagreeAfterTheRealReadAreAnError() throws IOException {
        java.nio.charset.Charset cyrillic = java.nio.charset.Charset.forName("windows-1251");
        Files.write(cyrillicFile(), "wrapper.conf.encoding=UTF-8\n".getBytes(cyrillic));
        Path file = dir.resolve("wrapper.conf");
        Files.write(file, "wrapper.conf.encoding=windows-1251\n#include \u043d\u0430\u0441\u0442.conf\n"
                .getBytes(cyrillic));

        ConfigException e = expectThrows(ConfigException.class, () -> loader(Map.of(), Map.of()).load(file));

        assertTrue(e.getMessage().contains("make the declarations agree"), e.getMessage());
    }

    @Test
    public void encodingNamesAcceptAliasesButNoVariables() throws IOException {
        Path alias = dir.resolve("wrapper.conf");
        Files.write(alias, "wrapper.conf.encoding= Cp1252 \nk=caf\u00e9\n".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals(value(loader(Map.of(), Map.of()).load(alias), "k"), "caf\u00e9");

        Path variable = write("wrapper.conf", "enc=UTF-8\nwrapper.conf.encoding=${enc}\n");
        ConfigException e = expectThrows(ConfigException.class, () -> loader(Map.of(), Map.of()).load(variable));
        assertTrue(e.getMessage().contains("variables are not resolved in wrapper.conf.encoding"), e.getMessage());

        ConfigException unknown = expectThrows(ConfigException.class,
                () -> loader(Map.of("wrapper.conf.encoding", "nope"), Map.of()).load(variable));
        assertEquals(unknown.location().orElseThrow(), ConfigLocation.of("system property wrapper.conf.encoding"));
    }

    @Test
    public void optionalIncludeWarningIsNotDuplicatedByTheProbe() throws IOException {
        Path file = write("wrapper.conf", "wrapper.conf.encoding=UTF-8\n#include missing.conf\n");

        LoadedConfig config = loader(Map.of(), Map.of()).load(file);

        assertEquals(config.warnings().stream().filter(w -> w.message().startsWith("optional include skipped")).count(),
                1L, config.warnings().toString());
    }

    @Test
    public void includePathReadingASecretSystemPropertyIsRefused() throws IOException {
        write("TopSecret123.conf", "leak=1\n");
        Path file = write("wrapper.conf", "#include ${sys:wrapper.ntservice.password}.conf\n"
                + "#include ${wrapper.ntservice.password}.conf\n#include ${sys:db.password}.conf\n");

        LoadedConfig config = loader(Map.of("wrapper.ntservice.password", "TopSecret123", "db.password", "TopSecret123"),
                Map.of()).load(file);

        assertEquals(config.get("leak"), Optional.empty());
        assertEquals(config.warnings().stream().filter(w -> w.message().contains("refers to a secret key")).count(), 3L,
                config.warnings().toString());
        assertTrue(config.warnings().stream().noneMatch(w -> w.toString().contains("TopSecret123")),
                config.warnings().toString());
    }

    @Test
    public void interpolationWarningsAreReported() throws IOException {
        Path file = write("wrapper.conf", "wrapper.working.dir=${nowhere}\n");

        LoadedConfig config = loader(Map.of(), Map.of()).load(file);

        assertEquals(config.warnings().size(), 1);
        assertTrue(config.warnings().get(0).message().contains("${nowhere}"), config.warnings().toString());
    }

    @Test
    public void environmentIsUsedForVariables() throws IOException {
        Path file = write("wrapper.conf", "wrapper.java.command=${JAVA_HOME}/bin/java\n");

        LoadedConfig config = loader(Map.of(), Map.of("JAVA_HOME", "C:/jdk8")).load(file);

        assertEquals(value(config, "wrapper.java.command"), "C:/jdk8/bin/java");
    }

    @Test
    public void missingFileIsAConfigException() {
        expectThrows(ConfigException.class, () -> loader(Map.of(), Map.of()).load(dir.resolve("missing.conf")));
    }

    @Test
    public void toStringShowsKeysAndLayersButNoValues() throws IOException {
        Path file = write("wrapper.conf", "wrapper.ntservice.password=s3cret\n");

        LoadedConfig config = loader(Map.of(), Map.of()).load(file);

        assertEquals(config.file(), file.toAbsolutePath().normalize());
        assertTrue(config.toString().contains("wrapper.ntservice.password=FILE"), config.toString());
        assertFalse(config.toString().contains("s3cret"), config.toString());
    }

    @Test
    public void loadedConfigRejectsInconsistentLayers() {
        ConfigEntry entry = entry("a", "1");
        expectThrows(IllegalArgumentException.class,
                () -> new LoadedConfig(dir, List.of(entry), Map.of(), List.of()));
        expectThrows(IllegalArgumentException.class,
                () -> new LoadedConfig(dir, List.of(), Map.of("a", ConfigLayer.FILE), List.of()));
        expectThrows(IllegalArgumentException.class, () -> new LoadedConfig(dir, List.of(entry, entry),
                Map.of("a", ConfigLayer.FILE, "b", ConfigLayer.FILE), List.of()));
    }

    @Test
    public void forCurrentProcessLoadsAFile() throws IOException {
        Path file = write("wrapper.conf", "wrapper.app.parameter.1=${sys:java.version}\n");

        LoadedConfig config = ConfigLoader.forCurrentProcess().load(file);

        assertEquals(value(config, "wrapper.app.parameter.1"), System.getProperty("java.version"));
    }

    /** A file with a Cyrillic name; skips the test where file names cannot hold it (ASCII POSIX locale). */
    private Path cyrillicFile() {
        try {
            return dir.resolve("\u043d\u0430\u0441\u0442.conf");
        } catch (java.nio.file.InvalidPathException e) {
            throw new org.testng.SkipException("file names cannot hold Cyrillic here (sun.jnu.encoding="
                    + System.getProperty("sun.jnu.encoding") + ")");
        }
    }

    private static ConfigLoader loader(Map<String, String> systemProperties, Map<String, String> environment) {
        return new ConfigLoader(systemProperties, environment, false, ConfigKeys.registry());
    }

    private static String constant(KeySpec spec) {
        return ((KeyDefault.Constant) spec.defaultValue()).value();
    }

    private static String value(LoadedConfig config, String key) {
        return config.get(key).orElseThrow(() -> new AssertionError("missing " + key)).value();
    }

    private static ConfigEntry entry(String key, String value) {
        return new ConfigEntry(key, value, ConfigLocation.of("test"));
    }

    private Path write(String relative, String content) throws IOException {
        Path path = dir.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }
}
