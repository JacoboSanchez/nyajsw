package io.jsl.nyajsw.cli;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import io.jsl.nyajsw.TestConfigs;
import io.jsl.nyajsw.config.LogLevel;
import io.jsl.nyajsw.config.WrapperConfig;
import io.jsl.nyajsw.launch.ClasspathExpander;
import io.jsl.nyajsw.launch.JavaCommandBuilder;
import io.jsl.nyajsw.launch.TestApplication;
import io.jsl.nyajsw.log.WrapperLog;
import io.jsl.nyajsw.platform.PlatformException;
import io.jsl.nyajsw.platform.ProcessJob;
import io.jsl.nyajsw.platform.ProcessTools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** When {@link Supervision} puts the application in a job object (plan P6.4). */
public class SupervisionTest {

    private Path dir;
    private ByteArrayOutputStream bytes;
    private WrapperLog log;

    @BeforeMethod
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("nyajsw-supervision").toRealPath();
        bytes = new ByteArrayOutputStream();
        log = WrapperLog.console(new PrintStream(bytes, true, StandardCharsets.UTF_8), LogLevel.INFO);
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws IOException {
        log.close();
        TestConfigs.delete(dir);
    }

    /** Counts the jobs it makes and whether they were closed. */
    private static final class CountingTools implements ProcessTools {

        int created;
        int closed;
        final List<Long> added = new java.util.concurrent.CopyOnWriteArrayList<>();
        boolean refuse;

        @Override
        public Optional<ProcessJob> createKillOnCloseJob() {
            if (refuse) {
                throw new PlatformException("cannot create a job object for the application: access is denied");
            }
            created++;
            return Optional.of(new ProcessJob() {
                @Override
                public void add(long pid) {
                    added.add(pid);
                }

                @Override
                public void terminate() {
                }

                @Override
                public void close() {
                    closed++;
                }
            });
        }
    }

    /** Runs supervision of {@code control} stopped before it starts, so that nothing is launched. */
    private void runStopped(String control, CountingTools tools) throws IOException {
        WrapperConfig config = TestConfigs.load(dir, "wrapper.java.app.mainclass=com.example.Main",
                "wrapper.control=" + control);
        Supervision supervision = new Supervision(config, log, JavaCommandBuilder.forThisJvm(),
                ClasspathExpander.forThisHost(), List.of(), (from, to) -> { }, tools);
        supervision.requestStop();
        supervision.run();
    }

    @Test
    public void anApplicationThatEndsWithTheWrapperGoesInAJobClosedAtTheEnd() throws IOException {
        for (String control : List.of("TIGHT", "APPLICATION")) {
            CountingTools tools = new CountingTools();

            runStopped(control, tools);

            assertEquals(tools.created, 1, control);
            assertEquals(tools.closed, 1, control);
        }
    }

    @Test(timeOut = 120_000)
    public void eachInvocationIsPutInTheJob() throws Exception {
        String classes = Path.of(TestApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString().replace('\\', '/');
        WrapperConfig config = TestConfigs.load(dir,
                "wrapper.java.command=" + Path.of(System.getProperty("java.home"), "bin", "java").toString()
                        .replace('\\', '/'),
                "wrapper.java.app.mainclass=" + TestApplication.class.getName(), "wrapper.java.classpath.1=" + classes,
                "wrapper.app.parameter.1=report", "wrapper.app.parameter.2=0");
        CountingTools tools = new CountingTools();
        List<Long> launched = new java.util.ArrayList<>();
        Supervision supervision = new Supervision(config, log, JavaCommandBuilder.forThisJvm(),
                ClasspathExpander.forThisHost(), List.of(), (from, to) -> { }, tools);

        supervision.run();

        String output = bytes.toString(StandardCharsets.UTF_8);
        output.lines().filter(line -> line.contains("jvm 1 started, pid "))
                .forEach(line -> launched.add(Long.parseLong(line.substring(line.lastIndexOf(' ') + 1))));
        assertEquals(tools.added, launched, output);
        assertEquals(tools.added.size(), 1, output);
        assertEquals(tools.closed, 1);
    }

    @Test
    public void anApplicationThatMayOutliveTheWrapperGoesInNoJob() throws IOException {
        for (String control : List.of("LOOSE", "WRAPPER")) {
            CountingTools tools = new CountingTools();

            runStopped(control, tools);

            assertEquals(tools.created, 0, control);
        }
    }

    @Test
    public void aJobThatCannotBeCreatedIsAnErrorAndSupervisionGoesOn() throws IOException {
        CountingTools tools = new CountingTools();
        tools.refuse = true;

        runStopped("TIGHT", tools);

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("cannot create a job object for the application: access is denied; the application"
                + " runs without it, so it may outlive the wrapper if that is killed"), output);
    }
}
