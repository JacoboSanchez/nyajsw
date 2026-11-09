package io.jsl.nyajsw.lifecycle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import io.jsl.nyajsw.TestConfigs;
import io.jsl.nyajsw.config.LifecycleConfig;
import io.jsl.nyajsw.config.LogLevel;
import io.jsl.nyajsw.launch.ExternalCommand;
import io.jsl.nyajsw.launch.TestApplication;
import io.jsl.nyajsw.log.WrapperLog;

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
import java.util.concurrent.TimeUnit;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** The {@code command} stop strategy, running {@link TestApplication} in a real JVM as the stop command. */
public class CommandStopStrategyTest {

    private static final Duration LONG_SHARE = Duration.ofMinutes(10);

    private Path dir;
    private ByteArrayOutputStream logBytes;
    private WrapperLog log;

    @BeforeMethod
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("nyajsw-stop-command").toRealPath();
        logBytes = new ByteArrayOutputStream();
        log = WrapperLog.console(new PrintStream(logBytes, true, StandardCharsets.UTF_8), LogLevel.INFO);
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws Exception {
        log.close();
        for (ProcessHandle left : ProcessHandle.current().descendants().toList()) {
            left.destroyForcibly();
            left.onExit().get(30, TimeUnit.SECONDS);
        }
        TestConfigs.delete(dir);
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static String classes() throws URISyntaxException {
        return Path.of(TestApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    private static List<String> testApplication(String... arguments) throws URISyntaxException {
        List<String> command = new ArrayList<>(List.of(java(), "-cp", classes(), TestApplication.class.getName()));
        command.addAll(List.of(arguments));
        return command;
    }

    private StopStrategy.Result stop(List<String> command, FakeApplication application, Duration share) {
        return new CommandStopStrategy(new ExternalCommand(command, dir)).requestStop(
                new StopStrategy.Context(application, log, share));
    }

    @Test(timeOut = 120_000)
    public void theRegistryBuildsItFromTheConfigurationAndRunsItInTheWorkingDirectory() throws Exception {
        // A classpath of "." finds TestApplication only when the command runs in the classes directory.
        LifecycleConfig config = TestConfigs.load(dir, "wrapper.working.dir=" + classes().replace('\\', '/'),
                "wrapper.stop.command=\"" + java().replace('\\', '/') + "\" -cp . " + TestApplication.class.getName()
                        + " report 0").lifecycle();
        StopChain chain = StopStrategyRegistry.defaults().chain(config, TestConfigs.load(dir).secrets());
        StopStrategy strategy = chain.graceful().get(0).strategy();

        StopStrategy.Result result = strategy.requestStop(new StopStrategy.Context(new FakeApplication(1), log,
                LONG_SHARE));

        assertEquals(result, StopStrategy.Result.REQUESTED);
        awaitLogged("the stop command " + java() + " exited with 0");
    }

    @Test(timeOut = 120_000)
    public void itAsksWithoutWaitingAndAnotherExitCodeIsAnError() throws Exception {
        log.close();
        log = WrapperLog.console(new PrintStream(logBytes, true, StandardCharsets.UTF_8), LogLevel.ERROR);

        StopStrategy.Result result = stop(testApplication("linger", "500", "3"), new FakeApplication(1), LONG_SHARE);

        assertEquals(result, StopStrategy.Result.REQUESTED);
        assertFalse(logBytes.toString(StandardCharsets.UTF_8).contains("exited"), "it waited: " + logBytes);
        awaitLogged("the stop command " + java() + " exited with 3"); // logged only as an error
    }

    @Test(timeOut = 120_000)
    public void aCommandThatOverrunsItsShareIsKilled() throws Exception {
        stop(testApplication("park"), new FakeApplication(1), Duration.ofMillis(1500));

        awaitLogged("the stop command " + java() + " did not end within 1.500 s; killed");
        awaitNoDescendants();
    }

    @Test(timeOut = 120_000)
    public void aCommandStillRunningWhenTheApplicationEndsIsKilled() throws Exception {
        FakeApplication application = new FakeApplication(1);
        stop(testApplication("park"), application, LONG_SHARE);
        while (ProcessHandle.current().descendants().findAny().isEmpty()) {
            Thread.sleep(50); // nothing tells when the command has started
        }

        application.endProcess();

        awaitLogged("the stop command " + java() + " was still running when jvm 1 ended; killed");
        awaitNoDescendants();
    }

    @Test(timeOut = 120_000)
    public void aProgramThatCannotBeRunIsAnErrorWithoutItsArguments() throws Exception {
        String missing = dir.resolve("missing.exe").toString();

        stop(List.of(missing, "--token", "s3cr3t"), new FakeApplication(1), LONG_SHARE);

        awaitLogged("cannot run the stop command " + missing + ": ");
        assertFalse(logBytes.toString(StandardCharsets.UTF_8).contains("s3cr3t"), logBytes.toString());
    }

    @Test
    public void itNeedsAStopCommand() throws IOException {
        assertEquals(expectThrows(IllegalArgumentException.class,
                () -> CommandStopStrategy.from(TestConfigs.load(dir).lifecycle())).getMessage(),
                "wrapper.stop.command is not set");
        CommandStopStrategy.from(TestConfigs.load(dir, "wrapper.stop.command=stop.bat").lifecycle()); // does not throw
    }

    private void awaitLogged(String text) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!logBytes.toString(StandardCharsets.UTF_8).contains(text)) {
            assertTrue(System.nanoTime() < deadline, "no '" + text + "' in " + logBytes);
            Thread.sleep(10); // the strategy's thread logs; nothing to wait on
        }
    }

    private static void awaitNoDescendants() throws Exception {
        for (ProcessHandle left : ProcessHandle.current().descendants().toList()) {
            left.onExit().get(30, TimeUnit.SECONDS); // a kill takes effect a moment later
        }
    }
}
