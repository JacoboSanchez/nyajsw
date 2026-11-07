package io.github.jacobosanchez.nyajsw.launch;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import io.github.jacobosanchez.nyajsw.config.ConfigKeys;
import io.github.jacobosanchez.nyajsw.config.ConfigLoader;
import io.github.jacobosanchez.nyajsw.config.LogLevel;
import io.github.jacobosanchez.nyajsw.config.SecretMasker;
import io.github.jacobosanchez.nyajsw.config.WrapperConfig;
import io.github.jacobosanchez.nyajsw.log.WrapperLog;
import io.github.jacobosanchez.nyajsw.platform.PlatformException;
import io.github.jacobosanchez.nyajsw.platform.ProcessJob;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** Starts {@link TestApplication} as a real child JVM, with the JVM that runs the tests. */
public class ApplicationProcessTest {

    private static final Duration GENEROUS = Duration.ofMinutes(1);

    private Path dir;
    private ByteArrayOutputStream logBytes;
    private WrapperLog log;
    private final List<ApplicationProcess> started = new ArrayList<>();
    private final List<Long> grandchildren = new ArrayList<>();

    @BeforeMethod
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("nyajsw-process");
        Files.createDirectories(dir.resolve("work"));
        logBytes = new ByteArrayOutputStream();
        log = WrapperLog.console(new PrintStream(logBytes, true, StandardCharsets.UTF_8), LogLevel.INFO);
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws Exception {
        for (ApplicationProcess process : started) {
            if (process.isAlive()) {
                process.terminateTree();
            }
            process.waitFor(GENEROUS);
        }
        started.clear();
        for (long pid : grandchildren) {
            Optional<ProcessHandle> handle = ProcessHandle.of(pid);
            if (handle.isPresent()) {
                handle.get().destroyForcibly();
                // Windows keeps the working directory in use until the process is really gone.
                handle.get().onExit().get(1, TimeUnit.MINUTES);
            }
        }
        grandchildren.clear();
        log.close();
        try (var paths = Files.walk(dir)) {
            for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String testClasses() throws URISyntaxException {
        return Path.of(TestApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString()
                .replace('\\', '/');
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString().replace('\\', '/');
    }

    private WrapperConfig config(String... extra) throws Exception {
        List<String> lines = new ArrayList<>(List.of(
                "wrapper.working.dir=work",
                "wrapper.java.command=" + java(),
                "wrapper.java.app.mainclass=" + TestApplication.class.getName(),
                "wrapper.java.classpath.1=" + testClasses()));
        lines.addAll(List.of(extra));
        Path file = Files.writeString(dir.resolve("wrapper.conf"), String.join("\n", lines) + "\n",
                StandardCharsets.UTF_8);
        return WrapperConfig.from(new ConfigLoader(Map.of(), Map.of(), false, ConfigKeys.registry()).load(file));
    }

    private ApplicationProcess start(WrapperConfig config, int invocation) {
        List<String> classpath = ClasspathExpander.forThisHost()
                .expand(config.java().classpath(), config.java().workingDir(), config.secrets()).entries();
        JavaCommand command = JavaCommandBuilder.forThisJvm().build(config.java(), classpath, config.secrets());
        ApplicationProcess process = ApplicationProcess.start(command, config.java(), invocation, config.secrets(), log);
        started.add(process);
        return process;
    }

    private List<String> logLines() {
        return logBytes.toString(StandardCharsets.UTF_8).lines().toList();
    }

    @Test(timeOut = 120_000)
    public void runsInTheWorkingDirectoryWithTheEnvironmentAndCapturesBothStreams() throws Exception {
        WrapperConfig config = config(
                "wrapper.app.parameter.1=report",
                "wrapper.app.parameter.2=7",
                "wrapper.app.parameter.3=a b",
                "wrapper.app.env.NYAJSW_TEST=hello",
                "wrapper.app.env.NYAJSW_REMOVED=");

        ApplicationProcess process = start(config, 3);
        Optional<Integer> code = process.waitFor(GENEROUS);

        assertEquals(code, Optional.of(7));
        assertEquals(process.name(), "jvm 3");
        List<String> lines = logLines();
        assertTrue(lines.contains("jvm 3    | args=[a b]"), lines.toString());
        String reported = lines.stream().filter(line -> line.startsWith("jvm 3    | dir=")).findFirst().orElseThrow()
                .substring("jvm 3    | dir=".length());
        // Compared as files: the child may report an 8.3 short name where the test sees the long one.
        assertTrue(Files.isSameFile(Path.of(reported), dir.resolve("work")), reported);
        assertTrue(lines.contains("jvm 3    | test=hello"), lines.toString());
        assertTrue(lines.contains("jvm 3    | removed=null"), lines.toString());
        assertTrue(lines.contains("jvm 3    | stdin-ended=true"), lines.toString());
        assertTrue(lines.contains("jvm 3    | to stderr"), lines.toString());
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("wrapper  | launching jvm 3: ")), lines.toString());
        assertTrue(lines.contains("wrapper  | jvm 3 exited with code 7"), lines.toString());
        // The exit is logged once, however often it is waited for.
        assertEquals(process.waitFor(), 7);
        assertEquals(process.onExit().get(1, TimeUnit.MINUTES), 7);
        assertEquals(logLines().stream().filter(line -> line.endsWith("exited with code 7")).count(), 1);
    }

    @Test(timeOut = 120_000)
    public void outputIsDecodedWithTheEncodingTheApplicationWrites() throws Exception {
        // Only the Java 8-17 property is written; this Java 21 child reads stdout.encoding, which must agree.
        ApplicationProcess process = start(config("wrapper.java.additional.1=-Dsun.stdout.encoding=ISO-8859-1",
                "wrapper.app.parameter.1=cafe"), 1);

        assertEquals(process.waitFor(GENEROUS), Optional.of(0));
        assertTrue(logLines().contains("jvm 1    | caf\u00e9"), logLines().toString());
    }

    @Test(timeOut = 120_000)
    public void waitingForARunningProcessTimesOut() throws Exception {
        ApplicationProcess process = start(config("wrapper.app.parameter.1=park"), 1);

        assertEquals(process.waitFor(Duration.ofMillis(50)), Optional.empty());
        assertTrue(process.isAlive());
    }

    @Test(timeOut = 120_000)
    public void terminatingKillsTheWholeTree() throws Exception {
        ApplicationProcess process = start(config("wrapper.app.parameter.1=parent"), 1);
        long grandchild = awaitGrandchildPid();
        ProcessHandle handle = ProcessHandle.of(grandchild).orElseThrow();

        process.terminateTree();

        assertTrue(process.waitFor(GENEROUS).isPresent());
        handle.onExit().get(1, TimeUnit.MINUTES);
        assertFalse(handle.isAlive());
    }

    /** A job that records what it is asked, and refuses to take a process when told to. */
    private static final class RecordingJob implements ProcessJob {

        final List<Long> added = new ArrayList<>();
        int terminated;
        boolean refuse;

        @Override
        public synchronized void add(long pid) {
            if (refuse) {
                throw new PlatformException("cannot put process " + pid + " in the job object: access is denied");
            }
            added.add(pid);
        }

        @Override
        public synchronized void terminate() {
            terminated++;
        }

        @Override
        public void close() {
        }
    }

    private ApplicationProcess start(WrapperConfig config, ProcessJob job) {
        List<String> classpath = ClasspathExpander.forThisHost()
                .expand(config.java().classpath(), config.java().workingDir(), config.secrets()).entries();
        JavaCommand command = JavaCommandBuilder.forThisJvm().build(config.java(), classpath, config.secrets());
        ApplicationProcess process = ApplicationProcess.start(command, config.java(), 1, config.secrets(), log,
                Optional.of(job));
        started.add(process);
        return process;
    }

    @Test(timeOut = 120_000)
    public void aStartedProcessGoesInTheJobWhichATerminationEndsFirst() throws Exception {
        RecordingJob job = new RecordingJob();
        ApplicationProcess process = start(config("wrapper.app.parameter.1=park"), job);

        assertEquals(job.added, List.of(process.pid()));

        process.terminateTree();

        assertEquals(job.terminated, 1);
        assertTrue(process.waitFor(GENEROUS).isPresent(), "the walk of the tree still ends the process");
    }

    @Test(timeOut = 120_000)
    public void aProcessTheJobRefusesRunsWithoutItAndSaysSo() throws Exception {
        RecordingJob job = new RecordingJob();
        job.refuse = true;
        ApplicationProcess process = start(config("wrapper.app.parameter.1=park"), job);

        assertTrue(process.isAlive());
        assertTrue(logLines().stream().anyMatch(line -> line.contains("jvm 1 runs outside the job object, so it may"
                + " outlive the wrapper if that is killed: cannot put process")), logLines().toString());

        process.terminateTree();

        assertEquals(job.terminated, 0, "a job it is not in is not ended for it");
        assertTrue(process.waitFor(GENEROUS).isPresent());
    }

    private long awaitGrandchildPid() throws InterruptedException {
        long deadline = System.nanoTime() + GENEROUS.toNanos();
        while (System.nanoTime() < deadline) {
            for (String line : logLines()) {
                if (line.startsWith("jvm 1    | child ")) {
                    long pid = Long.parseLong(line.substring("jvm 1    | child ".length()));
                    grandchildren.add(pid);
                    return pid;
                }
            }
            Thread.sleep(10); // bounded polling of real process output; no fake clock can stand in here
        }
        throw new AssertionError("the grandchild never reported its pid: " + logLines());
    }

    @Test(timeOut = 120_000)
    public void anExitIsReportedEvenWhileAGrandchildHoldsTheOutput() throws Exception {
        ApplicationProcess process = start(config("wrapper.app.parameter.1=orphan"), 1);
        CompletableFuture<Long> processEnded = process.onProcessEnd().thenApply(ignored -> System.nanoTime());
        CompletableFuture<Long> exitReported = process.onExit().thenApply(code -> System.nanoTime());

        assertEquals(process.waitFor(GENEROUS), Optional.of(0));
        // The process end comes first: the exit waits for the output, which the grandchild holds.
        long gap = exitReported.get(1, TimeUnit.MINUTES) - processEnded.get(1, TimeUnit.MINUTES);
        assertTrue(gap >= ApplicationProcess.DRAIN.dividedBy(2).toNanos(), "gap " + gap + " ns");
        awaitGrandchildPid(); // registers it for tearDown
        assertTrue(logLines().stream().anyMatch(line -> line.contains("still reading after jvm 1 ended")),
                logLines().toString());
    }

    @Test(timeOut = 120_000)
    public void theLaunchLineMasksSecrets() throws Exception {
        WrapperConfig config = config("wrapper.ntservice.password=S3cr3t-pw",
                "wrapper.java.additional.1=-Dpw=${wrapper.ntservice.password}", "wrapper.app.parameter.1=report",
                "wrapper.app.parameter.2=0");

        start(config, 1).waitFor(GENEROUS);

        String launching = logLines().stream().filter(line -> line.contains("launching jvm 1")).findFirst()
                .orElseThrow();
        assertFalse(launching.contains("S3cr3t-pw"), launching);
        assertTrue(launching.contains("-Dpw=****"), launching);
    }

    @Test(timeOut = 120_000)
    public void aSecretTheApplicationEchoesIsMaskedInTheLog() throws Exception {
        // The JVM rejects the unknown -X option and prints it back on stderr.
        WrapperConfig config = config("wrapper.ntservice.password=SuperSecret123",
                "wrapper.java.additional.1=-X${wrapper.ntservice.password}", "wrapper.app.parameter.1=report",
                "wrapper.app.parameter.2=0");

        start(config, 1).waitFor(GENEROUS);

        assertTrue(logLines().stream().noneMatch(line -> line.contains("SuperSecret123")), logLines().toString());
        assertTrue(logLines().stream().anyMatch(line -> line.startsWith("jvm 1") && line.contains("-X****")),
                logLines().toString());
    }

    @Test
    public void environmentNamesMatchIgnoringCaseUnderWindowsRules() {
        Map<String, String> inherited = new java.util.HashMap<>(Map.of("Path", "old", "SystemRoot", "C:\\Windows",
                "keep", "k"));

        ApplicationProcess.applyEnvironment(inherited, new java.util.LinkedHashMap<>(Map.of("PATH", "new",
                "systemroot", "")), true);

        assertEquals(inherited, Map.of("PATH", "new", "keep", "k"));
    }

    @Test
    public void aSystemPropertyWinsOverAFileSpellingOfTheSameWindowsVariable() throws Exception {
        Path file = Files.writeString(dir.resolve("env.conf"), "wrapper.app.env.Path=file1\nwrapper.app.env.PATH=file2\n",
                StandardCharsets.UTF_8);
        WrapperConfig config = WrapperConfig.from(new ConfigLoader(Map.of("wrapper.app.env.Path", "system"), Map.of(),
                false, ConfigKeys.registry()).load(file));
        Map<String, String> inherited = new java.util.HashMap<>(Map.of("path", "inherited"));

        ApplicationProcess.applyEnvironment(inherited, config.java().environment(), true);

        assertEquals(inherited, Map.of("Path", "system"));
    }

    @Test
    public void environmentNamesAreExactUnderPosixRules() {
        Map<String, String> inherited = new java.util.HashMap<>(Map.of("Path", "old", "HOME", "/root"));

        ApplicationProcess.applyEnvironment(inherited, new java.util.LinkedHashMap<>(Map.of("PATH", "new",
                "HOME", "")), false);

        assertEquals(inherited, Map.of("Path", "old", "PATH", "new"));
    }

    @Test
    public void anExecutableMayLeaveOutExeOnlyUnderWindowsRules() throws Exception {
        Path exe = Files.writeString(dir.resolve("tool.exe"), "x");
        String withoutSuffix = dir.resolve("tool").toString();
        SecretMasker secrets = config().secrets();

        ApplicationProcess.checkExecutable(exe.toString(), false, secrets);
        ApplicationProcess.checkExecutable(withoutSuffix, true, secrets);
        expectThrows(LaunchException.class, () -> ApplicationProcess.checkExecutable(withoutSuffix, false, secrets));
        ApplicationProcess.checkExecutable("java", false, secrets); // a bare name is left to the PATH
    }

    @Test
    public void missingJavaExecutableIsALaunchError() throws Exception {
        WrapperConfig config = config("wrapper.java.command=" + dir.resolve("no/such/java").toString().replace('\\', '/'),
                "wrapper.app.parameter.1=report");

        LaunchException e = expectThrows(LaunchException.class, () -> start(config, 1));

        assertTrue(e.getMessage().contains("does not exist"), e.getMessage());
    }

    @Test
    public void missingWorkingDirectoryIsALaunchError() throws Exception {
        WrapperConfig config = config("wrapper.working.dir=gone", "wrapper.app.parameter.1=report");

        LaunchException e = expectThrows(LaunchException.class, () -> start(config, 1));

        assertTrue(e.getMessage().contains("working directory"), e.getMessage());
    }

    @Test
    public void invocationsCountFromOne() throws Exception {
        WrapperConfig config = config("wrapper.app.parameter.1=report");

        expectThrows(IllegalArgumentException.class, () -> start(config, 0));
    }
}
