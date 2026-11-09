package io.jsl.nyajsw.dist;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * The zip the build packages (plan P8.1), unpacked: its layout, the manifests of its jars, and the
 * wrapper run from it, which must find the agent beside itself. Runs after the package phase (the
 * {@code packaged-distribution} execution in the POM), which names the zip; elsewhere it is skipped.
 * The zip is unpacked under a directory with blanks and parentheses, as {@code Program Files (x86)}
 * has. The scripts need Windows and are in the {@code windows-integration} group.
 */
public class PackagedDistributionTest {

    private static final String AGENT_CLASS = "io.jsl.nyajsw.agent.Agent";
    private static final String MAIN_CLASS_FILE = "io/jsl/nyajsw/cli/Main.class";
    private static final long TIMEOUT_SECONDS = 120;

    private Path dir;
    private Path home;
    private Path java;

    @BeforeClass
    public void unpack() throws IOException {
        String zip = System.getProperty("nyajsw.dist.zip");
        if (zip == null) {
            throw new SkipException("nyajsw.dist.zip is not set: run through the packaged-distribution execution");
        }
        dir = Files.createDirectories(Files.createTempDirectory("nyajsw-dist").resolve("a b (x86)"));
        unzip(Path.of(zip), dir);
        home = dir.resolve(System.getProperty("nyajsw.dist.name"));
        java = Path.of(System.getProperty("java.home"), "bin", "java");
    }

    @AfterClass(alwaysRun = true)
    public void delete() throws IOException {
        if (dir != null) {
            try (var paths = Files.walk(dir.getParent())) {
                for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    public void theZipHoldsTheJarsScriptsTemplateAndDocuments() {
        for (String file : List.of("nyajsw.jar", "nyajsw-agent.jar", "LICENSE", "README.md", "THIRD-PARTY.txt",
                "conf/wrapper.conf", "docs/COMPATIBILITY.md", "docs/probes.md", "docs/windows-service.md",
                "docs/migration-from-yajsw.md",
                "bin/nyajsw.bat", "bin/runConsole.bat", "bin/installService.bat", "bin/uninstallService.bat",
                "bin/startService.bat", "bin/stopService.bat", "bin/queryService.bat")) {
            assertTrue(Files.isRegularFile(home.resolve(file)), file);
        }
    }

    @Test
    public void theScriptsHaveWindowsLineEndings() throws IOException {
        try (var scripts = Files.list(home.resolve("bin"))) {
            for (Path script : scripts.toList()) {
                String text = Files.readString(script, StandardCharsets.US_ASCII);
                assertEquals(text.replace("\r\n", "").indexOf('\n'), -1, script + " has a bare LF");
                assertTrue(text.endsWith("\r\n"), script.toString());
            }
        }
    }

    @Test
    public void theThirdPartyNoticeNamesTheBundledJnaVersion() throws IOException {
        String notice = Files.readString(home.resolve("THIRD-PARTY.txt"), StandardCharsets.UTF_8);

        assertFalse(notice.contains("${"), notice);
        assertTrue(notice.matches("(?s).*JNA Platform, version \\d+\\.\\d+\\.\\d+\\s.*"), notice);
    }

    @Test
    public void theTemplateIsShippedUnfiltered() throws IOException {
        assertEquals(Files.readAllBytes(home.resolve("conf/wrapper.conf")),
                Files.readAllBytes(Path.of("src", "main", "dist", "conf", "wrapper.conf")));
    }

    @Test
    public void theWrapperJarRunsTheCommandLineAndIsNoAgent() throws IOException {
        try (JarFile jar = new JarFile(home.resolve("nyajsw.jar").toFile())) {
            Attributes manifest = jar.getManifest().getMainAttributes();

            assertEquals(manifest.getValue("Main-Class"), "io.jsl.nyajsw.cli.Main");
            assertEquals(manifest.getValue("Implementation-Version"), System.getProperty("nyajsw.dist.version"));
            assertNull(manifest.getValue("Premain-Class"), "the agent is found beside the wrapper, not in it");
            for (String entry : List.of("io/jsl/nyajsw/cli/Main.class",
                    "io/jsl/nyajsw/agent/AgentProtocol.class",
                    "com/sun/jna/Native.class", "com/sun/jna/platform/win32/Advapi32.class",
                    "com/sun/jna/win32-x86-64/jnidispatch.dll", "com/sun/jna/win32-aarch64/jnidispatch.dll",
                    "META-INF/LICENSE", "META-INF/AL2.0", "META-INF/LGPL2.1")) {
                assertTrue(jar.getEntry(entry) != null, entry);
            }
        }
    }

    @Test
    public void theWrapperJarHoldsTheWrapperAsJustBuilt() throws IOException {
        // A shaded jar left from an earlier build must not be packaged again with its old classes.
        byte[] built;
        try (InputStream in = ClassLoader.getSystemResourceAsStream(MAIN_CLASS_FILE)) {
            built = in.readAllBytes();
        }
        try (JarFile jar = new JarFile(home.resolve("nyajsw.jar").toFile());
                InputStream in = jar.getInputStream(jar.getEntry(MAIN_CLASS_FILE))) {
            assertEquals(in.readAllBytes(), built);
        }
    }

    @Test
    public void theAgentJarHoldsOnlyTheAgent() throws IOException {
        try (JarFile jar = new JarFile(home.resolve("nyajsw-agent.jar").toFile())) {
            assertEquals(jar.getManifest().getMainAttributes().getValue("Premain-Class"), AGENT_CLASS);
            List<String> foreign = jar.stream().map(ZipEntry::getName)
                    .filter(name -> name.endsWith(".class"))
                    .filter(name -> !name.startsWith("io/jsl/nyajsw/agent/"))
                    .toList();
            assertEquals(foreign, List.of());
        }
    }

    @Test
    public void theWrapperJarTellsItsVersion() throws Exception {
        Result result = run(List.of(java.toString(), "-jar", home.resolve("nyajsw.jar").toString(), "--version"));

        assertEquals(result.exitCode(), 0, result.output());
        assertEquals(result.output().strip(), "nyajsw " + System.getProperty("nyajsw.dist.version"));
    }

    @Test
    public void aConsoleRunFindsTheAgentBesideTheWrapper() throws Exception {
        Path conf = writeConfiguration(dir.resolve("console.conf"), 0);

        Result result = run(List.of(java.toString(), "-jar", home.resolve("nyajsw.jar").toString(), "-c",
                conf.toString()));

        assertEquals(result.exitCode(), 0, result.output());
        assertTrue(result.output().contains(HelloApplication.GREETING + "nyajsw-agent.jar"), result.output());
        assertFalse(result.output().contains("is not found beside the wrapper"), result.output());
    }

    @Test(groups = "windows-integration")
    public void theScriptsRunTheWrapperWithTheDefaultOrAGivenConfiguration() throws Exception {
        Path given = writeConfiguration(dir.resolve("given.conf"), 7);
        Path shipped = home.resolve("conf/wrapper.conf");
        byte[] template = Files.readAllBytes(shipped);
        try {
            writeConfiguration(shipped, 5);

            Result version = script("nyajsw.bat", "--version");
            Result byDefault = script("runConsole.bat");
            Result named = script("runConsole.bat", given.toString());
            // Quoted, as some installers write it; the script must not trip over a ")" in it either.
            Result throughJavaHome = run(scriptCommand("nyajsw.bat", "--version"),
                    Map.of("JAVA_HOME", '"' + System.getProperty("java.home") + '"'));

            assertEquals(version.exitCode(), 0, version.output());
            assertEquals(version.output().strip(), "nyajsw " + System.getProperty("nyajsw.dist.version"));
            assertEquals(byDefault.exitCode(), 5, "the application's exit code: " + byDefault.output());
            assertTrue(byDefault.output().contains(HelloApplication.GREETING + "nyajsw-agent.jar"),
                    byDefault.output());
            assertEquals(named.exitCode(), 7, "the application's exit code: " + named.output());
            assertEquals(throughJavaHome.exitCode(), 0, throughJavaHome.output());
            assertEquals(throughJavaHome.output().strip(), version.output().strip());
        } finally {
            Files.write(shipped, template);
        }
    }

    /** A configuration that runs {@link HelloApplication}, which exits with {@code exitCode}. */
    private Path writeConfiguration(Path conf, int exitCode) throws IOException {
        return Files.write(conf, List.of(
                "wrapper.java.command=" + slashes(java),
                "wrapper.java.app.mainclass=" + HelloApplication.class.getName(),
                "wrapper.java.classpath.1=" + slashes(Path.of(System.getProperty("nyajsw.test.classesDir"))),
                "wrapper.app.parameter.1=" + exitCode,
                "wrapper.logfile=" + slashes(conf.resolveSibling(conf.getFileName() + ".log"))),
                StandardCharsets.UTF_8);
    }

    private static String slashes(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    /** Runs a script of the distribution's {@code bin} through {@code cmd.exe}, on the test's Java. */
    private Result script(String name, String... arguments) throws Exception {
        return run(scriptCommand(name, arguments), Map.of("NYAJSW_JAVA", java.toString()));
    }

    private List<String> scriptCommand(String name, String... arguments) {
        // "call" first, so that cmd.exe does not take a quoted script path's quotes for its own.
        List<String> command = new ArrayList<>(List.of("cmd.exe", "/d", "/c", "call",
                home.resolve("bin").resolve(name).toString()));
        command.addAll(List.of(arguments));
        return command;
    }

    private Result run(List<String> command) throws Exception {
        return run(command, Map.of());
    }

    /** Runs {@code command} with the scripts' Java selection cleared from the environment, then {@code set}. */
    private Result run(List<String> command, Map<String, String> set) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
        builder.environment().keySet().removeIf(name -> List.of("NYAJSW_JAVA", "JAVA_HOME", "NYAJSW_OPTS")
                .contains(name.toUpperCase(Locale.ROOT)));
        builder.environment().putAll(set);
        Process process = builder.start();
        process.getOutputStream().close();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new AssertionError(command + " did not end within " + TIMEOUT_SECONDS + " s");
        }
        return new Result(process.exitValue(), output.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    private static String read(InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), Charset.defaultCharset());
        } catch (IOException e) {
            return "(output not readable: " + e + ")";
        }
    }

    private static void unzip(Path zip, Path target) throws IOException {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                Path file = target.resolve(entry.getName()).normalize();
                if (!file.startsWith(target)) {
                    throw new IOException("entry outside the zip's directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(file);
                } else {
                    Files.createDirectories(file.getParent());
                    Files.copy(in, file);
                }
            }
        }
    }

    private record Result(int exitCode, String output) {
    }
}
