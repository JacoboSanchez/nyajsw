package io.jsl.nyajsw.platform.windows;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import io.jsl.nyajsw.NyajswException;
import io.jsl.nyajsw.TestConfigs;
import io.jsl.nyajsw.cli.Main;
import io.jsl.nyajsw.config.ConfigException;
import io.jsl.nyajsw.config.WrapperConfig;
import io.jsl.nyajsw.launch.LaunchException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class ServiceCommandLineTest {

    private Path dir;
    private Path javaHome;
    private Path jar;
    private ServiceCommandLine commandLine;

    @BeforeMethod
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("nyajsw svc").toRealPath();
        javaHome = dir.resolve("jdk 21");
        jar = dir.resolve("lib/nyajsw.jar");
        commandLine = new ServiceCommandLine(javaHome, List.of(jar, dir.resolve("lib/jna.jar")), StandardCharsets.UTF_8);
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws IOException {
        TestConfigs.delete(dir);
    }

    private String javaw() {
        return javaHome.resolve("bin").resolve("javaw.exe").toString();
    }

    private String classpath() {
        return jar + ";" + dir.resolve("lib/jna.jar");
    }

    @Test
    public void theInstallingJvmsJavawRunsTheWrapperOnTheConfiguration() throws IOException {
        ServiceCommand command = commandLine.build(TestConfigs.load(dir));

        assertEquals(command.arguments(), List.of(javaw(), "-cp", classpath(), Main.class.getName(), "--service",
                dir.resolve("wrapper.conf").toString()));
        assertEquals(command.warnings(), List.of());
    }

    @Test
    public void theBinaryPathQuotesWhatHasBlanks() throws IOException {
        ServiceCommand command = commandLine.build(TestConfigs.load(dir, "wrapper.tmp.path=tmp dir"));

        assertEquals(command.binaryPath(), "\"" + javaw() + "\" \"-Djna.tmpdir=" + dir.resolve("tmp dir") + "\" -cp \""
                + classpath() + "\" " + Main.class.getName() + " --service \"" + dir.resolve("wrapper.conf") + "\"");
    }

    @Test
    public void wrapperJvmOptionsComeBeforeTheJnaTemporaryDirectory() throws IOException {
        WrapperConfig config = TestConfigs.load(dir, "wrapper.ntservice.additional.1=-Xmx64m",
                "wrapper.ntservice.additional.2=\"-Dname=a b\"", "wrapper.ntservice.additional.3=\"\"",
                "wrapper.ntservice.additional.4=-Djna.tmpdir=elsewhere", "wrapper.tmp.path=tmp");

        ServiceCommand command = commandLine.build(config);

        assertEquals(command.arguments().subList(1, 6), List.of("-Xmx64m", "-Dname=a b", "-Djna.tmpdir=elsewhere",
                "-Djna.tmpdir=" + dir.resolve("tmp"), "-cp"), "ours is the last");
        assertEquals(command.warnings(), List.of("an empty wrapper.ntservice.additional.<n> is skipped"));
    }

    @Test
    public void aJvmOptionWithAStrayQuoteIsRefusedMasked() throws IOException {
        WrapperConfig config = TestConfigs.load(dir, "wrapper.ntservice.password=hunter2",
                "wrapper.ntservice.additional.1=-Dp=${wrapper.ntservice.password}\"");

        LaunchException failure = expectThrows(LaunchException.class, () -> commandLine.build(config));

        assertTrue(failure.getMessage().startsWith("wrapper.ntservice.additional.<n> holds a double quote"),
                failure.getMessage());
        assertFalse(failure.getMessage().contains("hunter2"), failure.getMessage());
    }

    @Test
    public void theLoggableFormMasksEachArgument() throws IOException {
        WrapperConfig config = TestConfigs.load(dir, "wrapper.ntservice.password=hunter2",
                "wrapper.ntservice.additional.1=-Dp=${wrapper.ntservice.password}");

        ServiceCommand command = commandLine.build(config);

        assertTrue(command.binaryPath().contains("-Dp=hunter2"));
        assertTrue(command.loggable(config.secrets()).contains("-Dp=****"), command.loggable(config.secrets()));
        assertFalse(command.loggable(config.secrets()).contains("hunter2"));
        assertFalse(command.toString().contains("hunter2"));
    }

    @Test
    public void anArgumentTheCodePageCannotHoldIsAConfigurationError() throws IOException {
        ServiceCommandLine ascii = new ServiceCommandLine(javaHome, List.of(jar), StandardCharsets.US_ASCII);

        ConfigException failure = expectThrows(ConfigException.class,
                () -> ascii.build(TestConfigs.load(dir, "wrapper.tmp.path=tΩ")));

        assertTrue(failure.getMessage().startsWith("the service's command line cannot hold -Djna.tmpdir="
                + dir.resolve("tΩ") + ": the Java launcher reads it in the code page US-ASCII"), failure.getMessage());
    }

    @Test
    public void aVariableInTheCommandLineIsAWarning() throws IOException {
        ServiceCommand command = commandLine.build(TestConfigs.load(dir, "wrapper.ntservice.additional.1=-Dx=%TEMP%",
                "wrapper.ntservice.additional.2=-Dy=100%"));

        assertEquals(command.warnings(), List.of("Windows replaces %NAME% in a service's command line with the"
                + " variable's value at every start: -Dx=%TEMP%"));
    }

    @Test
    public void classpathEntriesAreMadeAbsoluteAndNormal() throws IOException {
        ServiceCommandLine relative = new ServiceCommandLine(javaHome, List.of(Path.of("bin", "..", "lib", "nyajsw.jar")),
                StandardCharsets.UTF_8);

        String classpath = relative.build(TestConfigs.load(dir)).arguments().get(2);

        assertEquals(classpath, Path.of("lib", "nyajsw.jar").toAbsolutePath().toString());
    }

    @Test
    public void anEmptyClasspathCannotMakeAServiceCommandLine() {
        NyajswException failure = expectThrows(NyajswException.class,
                () -> new ServiceCommandLine(javaHome, List.of(), StandardCharsets.UTF_8));

        assertTrue(failure.getMessage().contains("java.class.path is empty"), failure.getMessage());
    }

    @Test
    public void thisJvmGivesItsOwnJavaHomeAndClasspath() throws IOException {
        List<String> arguments = ServiceCommandLine.forThisJvm().build(TestConfigs.load(dir)).arguments();

        assertEquals(arguments.get(0), Path.of(System.getProperty("java.home")).toAbsolutePath().normalize()
                .resolve("bin").resolve("javaw.exe").toString());
        assertFalse(arguments.get(2).isEmpty());
    }

    @Test
    public void aConfiguredJava21RunsTheService() throws IOException {
        Path javaw = fakeJava("jre21", "JAVA_VERSION=\"21.0.13\"");
        ServiceCommand command = commandLine.build(TestConfigs.load(dir,
                "wrapper.ntservice.java.command=jre21/bin/javaw.exe"));

        assertEquals(command.arguments().get(0), javaw.toString());
        assertEquals(command.warnings(), List.of());
    }

    @Test
    public void aConfiguredOlderJavaIsAConfigurationError() throws IOException {
        fakeJava("jre8", "IMPLEMENTOR=\"x\"\nJAVA_VERSION=\"1.8.0_292\"");
        WrapperConfig config = TestConfigs.load(dir, "wrapper.ntservice.java.command=jre8/bin/javaw.exe");

        ConfigException failure = expectThrows(ConfigException.class, () -> commandLine.build(config));

        assertTrue(failure.getMessage().contains("wrapper.ntservice.java.command is a Java 8, but the wrapper needs"
                + " Java 21 or later"), failure.getMessage());
    }

    @Test
    public void aJavaWhoseVersionCannotBeToldIsAWarning() throws IOException {
        Path bin = Files.createDirectories(dir.resolve("unknown/bin"));
        Files.writeString(bin.resolve("javaw.exe"), "");

        ServiceCommand command = commandLine.build(TestConfigs.load(dir,
                "wrapper.ntservice.java.command=unknown/bin/javaw.exe"));

        assertEquals(command.warnings(), List.of("cannot tell which Java wrapper.ntservice.java.command is ("
                + bin.resolve("javaw.exe") + "); the wrapper needs Java 21 or later"));
    }

    @Test
    public void aConfiguredJavaThatIsNotThereIsAConfigurationError() throws IOException {
        WrapperConfig config = TestConfigs.load(dir, "wrapper.ntservice.java.command=jdk/bin/javaw.exe");

        ConfigException failure = expectThrows(ConfigException.class, () -> commandLine.build(config));

        assertTrue(failure.getMessage().contains("wrapper.ntservice.java.command names no file: "
                + dir.resolve("jdk/bin/javaw.exe")), failure.getMessage());
    }

    @Test
    public void aBareJavaNameIsAConfigurationError() throws IOException {
        WrapperConfig config = TestConfigs.load(dir, "wrapper.ntservice.java.command=javaw");

        ConfigException failure = expectThrows(ConfigException.class, () -> commandLine.build(config));

        assertTrue(failure.getMessage().startsWith("wrapper.ntservice.java.command=javaw has no directory"),
                failure.getMessage());
    }

    @Test
    public void theVersionComesFromTheReleaseFileWhenThereIsOne() throws IOException {
        assertEquals(ServiceCommandLine.javaVersion(fakeJava("a", "JAVA_VERSION=\"17\"")), OptionalInt.of(17));
        assertEquals(ServiceCommandLine.javaVersion(fakeJava("b", "JAVA_VERSION=\"1.8.0\"")), OptionalInt.of(8));
        assertEquals(ServiceCommandLine.javaVersion(fakeJava("c", "no version")), OptionalInt.empty());
        assertEquals(ServiceCommandLine.javaVersion(fakeJava("d", "JAVA_VERSION=\"99999999999\"")),
                OptionalInt.empty());
        Path withoutRelease = Files.createDirectories(dir.resolve("e/bin")).resolve("javaw.exe");
        assertEquals(ServiceCommandLine.javaVersion(withoutRelease), OptionalInt.empty());
    }

    @Test
    public void aJdk8sEmbeddedJreHasItsReleaseFileAbove() throws IOException {
        Path bin = Files.createDirectories(dir.resolve("jdk1.8.0_292/jre/bin"));
        Files.writeString(dir.resolve("jdk1.8.0_292/release"), "JAVA_VERSION=\"1.8.0_292\"");

        assertEquals(ServiceCommandLine.javaVersion(Files.writeString(bin.resolve("javaw.exe"), "")), OptionalInt.of(8));
    }

    private Path fakeJava(String home, String release) throws IOException {
        Path bin = Files.createDirectories(dir.resolve(home).resolve("bin"));
        Files.writeString(dir.resolve(home).resolve("release"), release);
        return Files.writeString(bin.resolve("javaw.exe"), "");
    }
}
