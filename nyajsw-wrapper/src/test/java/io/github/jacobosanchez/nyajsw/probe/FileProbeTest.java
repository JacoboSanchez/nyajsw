package io.github.jacobosanchez.nyajsw.probe;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.expectThrows;

import io.github.jacobosanchez.nyajsw.TestConfigs;
import io.github.jacobosanchez.nyajsw.config.ProbeConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class FileProbeTest {

    private Path dir;

    @BeforeMethod
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("nyajsw-file-probe").toRealPath();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws IOException {
        TestConfigs.delete(dir);
    }

    @Test
    public void passesWhileThePathExists() throws IOException {
        Path ready = dir.resolve("ready");
        FileProbe probe = new FileProbe(ready);

        assertEquals(probe.check(FakeRunningApplication.running()), ProbeResult.fail(ready + " does not exist"));
        Files.createFile(ready);
        assertEquals(probe.check(FakeRunningApplication.running()), ProbeResult.pass(ready + " exists"));
        Files.delete(ready);
        Files.createDirectory(ready);
        assertEquals(probe.check(FakeRunningApplication.running()), ProbeResult.pass(ready + " exists"));
    }

    @Test
    public void theRegistryBuildsItFromTheConfiguration() throws IOException {
        ProbeConfig config = TestConfigs.load(dir, "wrapper.readiness.file.path=ready").probes();

        ProbeRegistry.NamedProbe probe = ProbeRegistry.defaults().create(List.of("file"), config).get(0);

        Files.createFile(dir.resolve("ready"));
        assertEquals(probe.probe().check(FakeRunningApplication.running()),
                ProbeResult.pass(dir.resolve("ready") + " exists"));
        ProbeConfig without = TestConfigs.load(dir).probes();
        assertEquals(expectThrows(IllegalArgumentException.class, () -> FileProbe.from(without)).getMessage(),
                "the file probe needs wrapper.readiness.file.path");
    }
}
