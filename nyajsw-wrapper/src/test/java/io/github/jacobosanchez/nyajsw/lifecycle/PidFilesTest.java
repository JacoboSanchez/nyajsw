package io.github.jacobosanchez.nyajsw.lifecycle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import io.github.jacobosanchez.nyajsw.config.ConfigKeys;
import io.github.jacobosanchez.nyajsw.config.ConfigLoader;
import io.github.jacobosanchez.nyajsw.config.LogLevel;
import io.github.jacobosanchez.nyajsw.config.WrapperConfig;
import io.github.jacobosanchez.nyajsw.launch.ApplicationLauncher;
import io.github.jacobosanchez.nyajsw.launch.LaunchException;
import io.github.jacobosanchez.nyajsw.launch.RunningApplication;
import io.github.jacobosanchez.nyajsw.log.WrapperLog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class PidFilesTest {

    private Path dir;
    private ByteArrayOutputStream logBytes;
    private WrapperLog log;

    @BeforeMethod
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("nyajsw-pid");
        logBytes = new ByteArrayOutputStream();
        log = WrapperLog.console(new PrintStream(logBytes, true, StandardCharsets.UTF_8), LogLevel.DEBUG);
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws IOException {
        log.close();
        try (var paths = Files.walk(dir)) {
            for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** Pid files from a configuration file in the temporary directory, so relative paths resolve there. */
    private PidFiles pidFiles(String... lines) throws IOException {
        List<String> all = new ArrayList<>(List.of("wrapper.java.app.mainclass=Main"));
        all.addAll(List.of(lines));
        Path conf = Files.write(dir.resolve("wrapper.conf"), all);
        WrapperConfig config = WrapperConfig.from(
                new ConfigLoader(Map.of(), Map.of(), false, ConfigKeys.registry()).load(conf));
        return new PidFiles(config.lifecycle(), config.secrets(), log);
    }

    private String logged() {
        return logBytes.toString(StandardCharsets.UTF_8);
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.US_ASCII);
    }

    @Test
    public void theWrapperPidFileIsWrittenAndRemoved() throws IOException {
        PidFiles files = pidFiles("wrapper.pidfile=run/wrapper.pid");
        Path file = dir.resolve("run/wrapper.pid");

        files.wrapperStarted(4242);
        assertEquals(read(file), "4242" + System.lineSeparator());

        files.wrapperEnded(4242);
        assertFalse(Files.exists(file));
        try (var left = Files.list(file.getParent())) {
            assertEquals(left.count(), 0L, "no temporary file is left behind");
        }
    }

    @Test
    public void aStaleFileIsOverwritten() throws IOException {
        Path file = Files.writeString(dir.resolve("wrapper.pid"), "999\n");
        PidFiles files = pidFiles("wrapper.pidfile=wrapper.pid");

        files.wrapperStarted(17);

        assertEquals(read(file).strip(), "17");
    }

    @Test
    public void aFileThatNowHoldsAnotherPidIsLeft() throws IOException {
        PidFiles files = pidFiles("wrapper.pidfile=wrapper.pid");
        Path file = dir.resolve("wrapper.pid");
        files.wrapperStarted(17);
        Files.writeString(file, "18\n"); // another instance took over

        files.wrapperEnded(17);

        assertEquals(read(file).strip(), "18");
        assertTrue(logged().contains("no longer holds 17; left"), logged());
    }

    @Test
    public void aFileAlreadyGoneIsNoProblem() throws IOException {
        PidFiles files = pidFiles("wrapper.pidfile=wrapper.pid");

        files.wrapperEnded(17);

        assertFalse(logged().contains("cannot"), logged());
    }

    @Test
    public void aFileThatCannotBeWrittenIsAnErrorButNotAFailure() throws IOException {
        Files.writeString(dir.resolve("blocker"), "a file, not a directory");
        PidFiles files = pidFiles("wrapper.pidfile=blocker/wrapper.pid");

        files.wrapperStarted(17);

        assertTrue(logged().contains("cannot write the pid file " + dir.resolve("blocker/wrapper.pid")
                + " (wrapper.pidfile)"), logged());
    }

    private final List<FakeApplication> launched = new ArrayList<>();

    private ApplicationLauncher tracking(PidFiles files) {
        launched.clear();
        return files.tracking(invocation -> {
            FakeApplication application = new FakeApplication(invocation);
            launched.add(application);
            return application;
        });
    }

    @Test
    public void theApplicationPidFileFollowsEachInvocation() throws IOException {
        PidFiles files = pidFiles("wrapper.java.pidfile=java.pid");
        Path file = dir.resolve("java.pid");
        ApplicationLauncher tracking = tracking(files);

        RunningApplication first = tracking.launch(1);
        CompletableFuture<Integer> firstExit = first.onExit(); // as the supervisor does right after the launch
        assertEquals(read(file).strip(), Long.toString(first.pid()));
        launched.get(0).exit(0);
        assertFalse(Files.exists(file));
        assertEquals(firstExit.join(), 0);

        RunningApplication second = tracking.launch(2);
        assertEquals(read(file).strip(), Long.toString(second.pid()));
    }

    @Test
    public void theExitIsReportedOnlyAfterThePidFileIsGone() throws IOException {
        PidFiles files = pidFiles("wrapper.java.pidfile=java.pid");
        Path file = dir.resolve("java.pid");
        RunningApplication application = tracking(files).launch(1);
        List<Boolean> existedWhenReported = new ArrayList<>();
        application.onExit().thenRun(() -> existedWhenReported.add(Files.exists(file)));

        launched.get(0).exit(3);

        assertEquals(existedWhenReported, List.of(false));
    }

    @Test
    public void anOverlappingLaterInvocationKeepsItsFile() throws IOException {
        PidFiles files = pidFiles("wrapper.java.pidfile=java.pid");
        Path file = dir.resolve("java.pid");
        ApplicationLauncher tracking = tracking(files);
        RunningApplication first = tracking.launch(1);
        first.onExit();
        RunningApplication second = tracking.launch(2);

        launched.get(0).exit(0);

        assertEquals(read(file).strip(), Long.toString(second.pid()));
        assertTrue(logged().contains("no longer holds " + first.pid() + "; left"), logged());
    }

    @Test
    public void anExitThatCannotBeObservedLeavesTheFile() throws IOException {
        PidFiles files = pidFiles("wrapper.java.pidfile=java.pid");
        RunningApplication application = tracking(files).launch(1);
        CompletableFuture<Integer> exit = application.onExit();

        launched.get(0).loseTrack(new IllegalStateException("reaper gone"));

        assertTrue(exit.isCompletedExceptionally());
        assertTrue(Files.exists(dir.resolve("java.pid")), "the process may still run");
    }

    @Test
    public void anOnExitThatThrowsFailsWhereTheSupervisorAsksForIt() throws IOException {
        PidFiles files = pidFiles("wrapper.java.pidfile=java.pid");
        ApplicationLauncher launcher = files.tracking(invocation -> new FakeApplication(invocation).failingOnExit());

        RunningApplication application = launcher.launch(1); // the launch itself succeeds

        expectThrows(IllegalStateException.class, application::onExit);
        application.terminateTree();
        assertFalse(application.isAlive(), "calls reach the real application");
    }

    @Test
    public void aLaunchErrorWritesNothing() throws IOException {
        PidFiles files = pidFiles("wrapper.java.pidfile=java.pid");
        ApplicationLauncher launcher = files.tracking(invocation -> {
            throw new LaunchException("cannot start jvm " + invocation);
        });

        expectThrows(LaunchException.class, () -> launcher.launch(1));

        assertFalse(Files.exists(dir.resolve("java.pid")));
    }

    @Test
    public void aFileThatCannotBeRemovedIsAnErrorButNotAFailure() throws IOException {
        PidFiles files = pidFiles("wrapper.pidfile=wrapper.pid");
        Files.createDirectories(dir.resolve("wrapper.pid")); // a directory cannot be read as a pid file

        files.wrapperEnded(17);

        assertTrue(logged().contains("cannot remove the pid file " + dir.resolve("wrapper.pid")
                + " (wrapper.pidfile)"), logged());
    }

    @Test
    public void aSecretInThePathIsMaskedInMessages() throws IOException {
        Files.writeString(dir.resolve("blocker"), "a file, not a directory");
        PidFiles files = pidFiles("wrapper.ntservice.password=Hidden-Pw-42",
                "wrapper.pidfile=blocker/Hidden-Pw-42/wrapper.pid");

        files.wrapperStarted(17);

        assertTrue(logged().contains("cannot write the pid file"), logged());
        assertFalse(logged().contains("Hidden-Pw-42"), logged());
    }

    @Test
    public void aLeftoverTemporaryFileIsReused() throws IOException {
        Files.writeString(dir.resolve("wrapper.pid.tmp"), "garbage from a crash, longer than a pid");
        PidFiles files = pidFiles("wrapper.pidfile=wrapper.pid");

        files.wrapperStarted(17);

        assertEquals(read(dir.resolve("wrapper.pid")).strip(), "17");
        assertFalse(Files.exists(dir.resolve("wrapper.pid.tmp")));
    }

    @Test
    public void withoutTheKeysNothingIsWrittenAndTheLauncherIsUnchanged() throws IOException {
        PidFiles files = pidFiles();
        ApplicationLauncher launcher = FakeApplication::new;

        files.wrapperStarted(17);

        assertSame(files.tracking(launcher), launcher);
        try (var left = Files.list(dir)) {
            assertEquals(left.map(path -> path.getFileName().toString()).toList(), List.of("wrapper.conf"));
        }
    }
}
