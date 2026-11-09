package io.jsl.nyajsw.platform.windows;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import io.jsl.nyajsw.launch.TestApplication;
import io.jsl.nyajsw.platform.HostOs;
import io.jsl.nyajsw.platform.ProcessJob;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Real job objects (plan P6.4) with {@link TestApplication} processes: closing the job, or ending
 * it, ends what is in it, grandchildren included. Needs no elevation, but Windows; excluded by
 * default with the other {@code windows-integration} tests.
 */
@Test(groups = "windows-integration")
public class WindowsProcessToolsIntegrationTest {

    private final List<Process> started = new ArrayList<>();

    @BeforeMethod(alwaysRun = true)
    public void setUp() {
        if (!HostOs.isWindows()) {
            throw new SkipException("job objects exist on Windows only");
        }
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws Exception {
        for (Process process : started) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(1, TimeUnit.MINUTES);
        }
        started.clear();
    }

    private Process start(String mode) throws Exception {
        String classes = Path.of(TestApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString();
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classes, TestApplication.class.getName(), mode).redirectErrorStream(true).start();
        started.add(process);
        return process;
    }

    private static String firstLine(Process process) throws Exception {
        return new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)).readLine();
    }

    @Test(timeOut = 120_000)
    public void closingTheJobEndsTheProcessInIt() throws Exception {
        ProcessJob job = new WindowsProcessTools(new JnaProcessApi()).createKillOnCloseJob().orElseThrow();
        Process process;
        try {
            process = start("park");
            job.add(process.pid());
            assertTrue(firstLine(process).contains("parked"));
        } finally {
            job.close();
        }

        assertTrue(process.waitFor(1, TimeUnit.MINUTES), "the process outlived its job");
    }

    @Test(timeOut = 120_000)
    public void endingTheJobEndsWhatTheProcessStartedAfterJoiningIt() throws Exception {
        try (ProcessJob job = new WindowsProcessTools(new JnaProcessApi()).createKillOnCloseJob().orElseThrow()) {
            Process process = start("parent");
            job.add(process.pid()); // before its JVM is up far enough to start the grandchild
            String line = firstLine(process);
            assertTrue(line.startsWith("child "), line);
            ProcessHandle grandchild = ProcessHandle.of(Long.parseLong(line.substring("child ".length()))).orElseThrow();

            job.terminate();

            assertTrue(process.waitFor(1, TimeUnit.MINUTES));
            grandchild.onExit().get(1, TimeUnit.MINUTES);
            assertFalse(grandchild.isAlive());
        }
    }
}
