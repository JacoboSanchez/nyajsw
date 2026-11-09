package io.jsl.nyajsw.lifecycle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import io.jsl.nyajsw.TestConfigs;
import io.jsl.nyajsw.config.ConfigException;
import io.jsl.nyajsw.config.LifecycleConfig;
import io.jsl.nyajsw.config.LogLevel;
import io.jsl.nyajsw.launch.ExternalCommand;
import io.jsl.nyajsw.launch.TestApplication;
import io.jsl.nyajsw.log.WrapperLog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The {@code command} stop strategy, running {@link TestApplication} in a real JVM as the stop
 * command. Commands that must be seen running write their pid to a file, so that a test looks at
 * its own processes only.
 */
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
        TestApplication.killAll(dir);
        TestConfigs.delete(dir);
    }

    private CommandStopStrategy strategy(List<String> command) {
        return new CommandStopStrategy(new ExternalCommand(command, dir));
    }

    private static StopStrategy.Context context(FakeApplication application, WrapperLog log, Duration share) {
        return new StopStrategy.Context(application, log, share);
    }

    /** A {@code park} command that writes its pid to {@code <name>.pid}. */
    private List<String> parked(String name) {
        return TestApplication.command("pid", dir.resolve(name + ".pid").toString(), "park");
    }

    private String logged() {
        return logBytes.toString(StandardCharsets.UTF_8);
    }

    @Test(timeOut = 120_000)
    public void theRegistryBuildsItFromTheConfigurationAndRunsItInTheWorkingDirectory() throws Exception {
        // A classpath of "." finds TestApplication only when the command runs in the classes directory.
        String classes = Path.of(TestApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString();
        LifecycleConfig config = TestConfigs.load(dir, "wrapper.working.dir=" + classes.replace('\\', '/'),
                "wrapper.stop.command=\"" + TestApplication.java().replace('\\', '/') + "\" -cp . "
                        + TestApplication.class.getName() + " report 0").lifecycle();
        StopChain chain = StopStrategyRegistry.defaults().chain(config, TestConfigs.load(dir).secrets());
        StopStrategy strategy = chain.graceful().get(0).strategy();

        StopStrategy.Result result = strategy.requestStop(context(new FakeApplication(1), log, LONG_SHARE));

        assertEquals(result, StopStrategy.Result.REQUESTED);
        awaitLogged("the stop command " + TestApplication.java() + " exited with 0");
    }

    @Test(timeOut = 120_000)
    public void itAsksWithoutWaitingAndAnotherExitCodeIsAnError() throws Exception {
        log.close();
        log = WrapperLog.console(new PrintStream(logBytes, true, StandardCharsets.UTF_8), LogLevel.ERROR);

        StopStrategy.Result result = strategy(TestApplication.command("linger", "500", "3"))
                .requestStop(context(new FakeApplication(1), log, LONG_SHARE));

        assertEquals(result, StopStrategy.Result.REQUESTED);
        assertFalse(logged().contains("exited"), "it waited: " + logged());
        awaitLogged("the stop command " + TestApplication.java() + " exited with 3"); // logged only as an error
    }

    @Test(timeOut = 120_000)
    public void aCommandThatOverrunsItsShareIsKilled() throws Exception {
        strategy(parked("overrun")).requestStop(context(new FakeApplication(1), log, Duration.ofMillis(1500)));
        ProcessHandle command = TestApplication.awaitPid(dir.resolve("overrun.pid"));

        awaitLogged("the stop command " + TestApplication.java() + " did not end within 1.500 s; killed");
        command.onExit().get(30, TimeUnit.SECONDS); // a kill takes effect a moment later
    }

    @Test(timeOut = 120_000)
    public void aCommandStillRunningWhenTheApplicationEndsIsKilled() throws Exception {
        FakeApplication application = new FakeApplication(1);
        strategy(parked("ended")).requestStop(context(application, log, LONG_SHARE));
        ProcessHandle command = TestApplication.awaitPid(dir.resolve("ended.pid"));

        application.endProcess();

        command.onExit().get(30, TimeUnit.SECONDS);
        awaitLogged("the stop command " + TestApplication.java() + " was ended, since jvm 1 has ended");
    }

    @Test(timeOut = 120_000)
    public void closingKillsACommandStillRunningAndWaitsUntilItIsLogged() throws Exception {
        CommandStopStrategy strategy = strategy(parked("closed"));
        strategy.requestStop(context(new FakeApplication(1), log, LONG_SHARE));
        ProcessHandle command = TestApplication.awaitPid(dir.resolve("closed.pid"));

        strategy.close();

        // Once close has returned, the kill is done and logged, before supervision closes the log.
        assertTrue(logged().contains("the stop command " + TestApplication.java()
                + " was ended, since supervision has ended"), logged());
        command.onExit().get(30, TimeUnit.SECONDS);
        strategy.close(); // nothing left: returns at once
    }

    @Test(timeOut = 120_000)
    public void anInterruptedCloseStillWaitsAndKeepsTheInterrupt() throws Exception {
        CommandStopStrategy strategy = strategy(parked("interrupted"));
        strategy.requestStop(context(new FakeApplication(1), log, LONG_SHARE));
        ProcessHandle command = TestApplication.awaitPid(dir.resolve("interrupted.pid"));

        // As after Supervisor.run() was stopped by an interrupt: the status is set when supervision closes.
        Thread.currentThread().interrupt();
        try {
            strategy.close();
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt is kept");
        } finally {
            Thread.interrupted();
        }

        assertTrue(logged().contains("was ended, since supervision has ended"), logged());
        command.onExit().get(30, TimeUnit.SECONDS);
    }

    @Test(timeOut = 120_000)
    public void closingRightAfterTheRequestStillEndsTheCommand() {
        CommandStopStrategy strategy = strategy(parked("early"));

        strategy.requestStop(context(new FakeApplication(1), log, LONG_SHARE));
        strategy.close();

        assertTrue(logged().contains("was ended, since supervision has ended"), logged());
    }

    @Test
    public void anApplicationThatHasEndedIsNotAsked() {
        FakeApplication application = new FakeApplication(1);
        application.endProcess();

        StopStrategy.Result result = strategy(parked("never")).requestStop(context(application, log, LONG_SHARE));

        assertEquals(result, StopStrategy.Result.NOT_APPLICABLE);
        assertFalse(Files.exists(dir.resolve("never.pid")));
    }

    @Test(timeOut = 120_000)
    public void aProgramThatCannotBeRunIsAnErrorWithoutItsArguments() throws Exception {
        String missing = dir.resolve("missing.exe").toString();

        strategy(List.of(missing, "--token", "s3cr3t")).requestStop(context(new FakeApplication(1), log, LONG_SHARE));

        awaitLogged("cannot run the stop command " + missing + ": ");
        assertFalse(logged().contains("s3cr3t"), logged());
    }

    @Test
    public void itNeedsAStopCommand() throws IOException {
        assertEquals(expectThrows(ConfigException.class,
                () -> CommandStopStrategy.from(TestConfigs.load(dir).lifecycle())).getMessage(),
                "wrapper.stop.command is not set");
        CommandStopStrategy.from(TestConfigs.load(dir, "wrapper.stop.command=stop.bat").lifecycle()); // no error
    }

    private void awaitLogged(String text) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!logged().contains(text)) {
            assertTrue(System.nanoTime() < deadline, "no '" + text + "' in " + logged());
            Thread.sleep(10); // the strategy's thread logs; nothing to wait on
        }
    }
}
