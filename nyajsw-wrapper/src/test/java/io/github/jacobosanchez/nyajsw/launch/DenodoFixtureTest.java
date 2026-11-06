package io.github.jacobosanchez.nyajsw.launch;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import io.github.jacobosanchez.nyajsw.config.ConfigKeys;
import io.github.jacobosanchez.nyajsw.config.ConfigLoader;
import io.github.jacobosanchez.nyajsw.config.ConfigWarning;
import io.github.jacobosanchez.nyajsw.config.WrapperConfig;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * The owner's installed Denodo Virtual DataPort service configuration, unchanged, turned into the
 * command line nyajsw would run (plan Q1, P1.9). Paths are compared as the code resolves them, so the
 * test also passes where {@code C:/Denodo} is not an absolute path.
 */
public class DenodoFixtureTest {

    /** The fixture's env.Path (fixture.properties). */
    private static final String PATH = "C:\\Windows\\system32;C:\\Windows";

    private WrapperConfig config;
    private List<String> arguments;

    @BeforeClass
    public void load() throws Exception {
        Path file = Path.of(getClass().getResource("/conf/fixtures/denodo-vdp/conf/vdp/service.conf").toURI());
        config = WrapperConfig.from(new ConfigLoader(Map.of(), Map.of("Path", PATH), true, ConfigKeys.registry())
                .load(file));
        arguments = new JavaCommandBuilder(Path.of("jdk21"), true)
                .build(config.java(), List.of("lib/denodo-commons-launcher-util.jar"), config.secrets()).arguments();
    }

    private Path workingDir() {
        return config.java().workingDir();
    }

    @Test
    public void theEscapedCommaReachesTheJvmAsAComma() {
        assertTrue(arguments.contains("-Djava.locale.providers=COMPAT,SPI"), arguments.toString());
    }

    @Test
    public void quotedOptionsAreUnquoted() {
        assertTrue(arguments.contains("-XX:+DisableExplicitGC"), arguments.toString());
        assertTrue(arguments.contains("-XX:+UseG1GC"), arguments.toString());
        assertTrue(arguments.contains("-XX:ReservedCodeCacheSize=256m"), arguments.toString());
    }

    @Test
    public void escapedBackslashesInArgumentsAreSingle() {
        assertTrue(arguments.contains("C:/Denodo/9/VDP9.4b\\conf\\vdp"), arguments.toString());
        assertTrue(arguments.stream().noneMatch(argument -> argument.contains("\\\\")), arguments.toString());
    }

    @Test
    public void commandLineHasTheExpectedShape() {
        assertEquals(arguments.get(0), workingDir().resolve("C:/Denodo/9/VDP9.4b/jre/bin/java.exe").normalize()
                .toString());
        assertEquals(arguments.subList(1, 3), List.of("-Xms3m", "-Xmx4096m"));
        String libraryPath = arguments.get(3);
        assertTrue(libraryPath.startsWith("-Djava.library.path="
                + workingDir().resolve("C:/Denodo/9/VDP9.4b/jre/bin").normalize() + ";"), libraryPath);
        // ${path} reads the Path variable ignoring case, as on Windows, and its two paths are split.
        assertTrue(libraryPath.contains(";" + workingDir().resolve("C:\\Windows\\system32").normalize() + ";"
                + workingDir().resolve("C:\\Windows").normalize() + ";"), libraryPath);
        assertTrue(libraryPath.endsWith(";" + workingDir()), libraryPath);
        int main = arguments.indexOf("com.denodo.util.launcher.Launcher");
        assertEquals(arguments.get(main - 2), "-classpath");
        assertEquals(arguments.get(main + 1), "com.denodo.vdb.vdbinterface.server.VDBManagerImpl");
        assertEquals(arguments.get(arguments.size() - 1), "C:/Denodo/9/VDP9.4b\\lib\\util-contrib");
        // wrapper.app.parameter.23 is missing in the file, so two --lib follow each other, as YAJSW passes them.
        int extensions = arguments.indexOf("C:/Denodo/9/VDP9.4b\\extensions");
        assertEquals(arguments.subList(extensions - 2, extensions), List.of("--lib", "--lib"));
        // 25 JVM options (wrapper.java.additional.8 is missing), the heap, the library path and the four
        // output-encoding options.
        assertEquals(arguments.stream().filter(argument -> argument.startsWith("-D") || argument.startsWith("-X")
                || argument.startsWith("--add-opens")).count(), 2 + 1 + 4 + 25);
    }

    @Test
    public void classpathEntryResolvesAgainstTheWorkingDirectory() {
        assertEquals(config.java().classpath(), List.of("..\\lib\\denodo-commons-launcher-util.jar"));
        assertEquals(workingDir(), config.file().getParent().resolve("C:/Denodo/9/VDP9.4b/bin/").normalize());
    }

    @Test
    public void warningsNameOnlyWhatNyajswDoesNotDoYet() {
        List<String> messages = config.warnings().stream().map(ConfigWarning::message).toList();

        assertTrue(messages.stream().noneMatch(message -> message.startsWith("wrapper.java.")
                || message.startsWith("wrapper.app.")), messages.toString());
        assertTrue(messages.contains("wrapper.syslog.<setting> (1 key) is not supported by nyajsw; it has no effect"),
                messages.toString());
        assertFalse(messages.isEmpty());
    }
}
