package io.github.jacobosanchez.nyajsw.probe;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.expectThrows;

import io.github.jacobosanchez.nyajsw.config.ConfigKeys;
import io.github.jacobosanchez.nyajsw.config.ConfigLoader;
import io.github.jacobosanchez.nyajsw.config.ProbeConfig;
import io.github.jacobosanchez.nyajsw.config.WrapperConfig;
import io.github.jacobosanchez.nyajsw.launch.RunningApplication;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.testng.annotations.Test;

public class ProbeRunnerTest {

    private static final Duration SECOND = Duration.ofSeconds(1);

    /** An application that is alive as long as {@code alive} says. */
    private static RunningApplication application(boolean alive) {
        return new RunningApplication() {
            @Override
            public String name() {
                return "jvm 1";
            }

            @Override
            public long pid() {
                return 42;
            }

            @Override
            public boolean isAlive() {
                return alive;
            }

            @Override
            public CompletableFuture<Integer> onExit() {
                return new CompletableFuture<>();
            }

            @Override
            public void terminateTree() {
            }
        };
    }

    private static ProbeRegistry.NamedProbe probe(String type, Probe probe) {
        return new ProbeRegistry.NamedProbe(type, probe);
    }

    @Test
    public void theProcessProbePassesWhileTheProcessRuns() {
        ProcessProbe probe = new ProcessProbe();

        assertEquals(probe.check(new ProbeContext(application(true))), ProbeResult.pass("jvm 1 is running"));
        assertEquals(probe.check(new ProbeContext(application(false))), ProbeResult.fail("jvm 1 is not running"));
    }

    @Test
    public void allProbesMustPassAndTheFirstFailureIsReported() {
        List<String> checked = new ArrayList<>();
        ProbeRunner runner = new ProbeRunner(List.of(
                probe("a", context -> {
                    checked.add("a");
                    return ProbeResult.pass("fine");
                }),
                probe("b", context -> {
                    checked.add("b");
                    return ProbeResult.fail("port closed");
                }),
                probe("c", context -> {
                    checked.add("c");
                    return ProbeResult.pass("fine");
                })), SECOND);

        assertEquals(runner.check(new ProbeContext(application(true))), ProbeResult.fail("b: port closed"));
        assertEquals(checked, List.of("a", "b"), "checking stops at the first failure");
    }

    @Test
    public void whenAllPassTheResultNamesTheTypes() {
        ProbeRunner runner = new ProbeRunner(List.of(probe("process", new ProcessProbe()),
                probe("other", context -> ProbeResult.pass("fine"))), SECOND);

        assertEquals(runner.check(new ProbeContext(application(true))), ProbeResult.pass("process,other"));
        assertEquals(runner.types(), "process,other");
    }

    @Test
    public void noProbesMeansReady() {
        ProbeRunner runner = new ProbeRunner(List.of(), SECOND);

        assertEquals(runner.check(new ProbeContext(application(false))), ProbeResult.pass("no probes"));
    }

    @Test
    public void aProbeThatThrowsOrAnswersNothingFailsWithoutItsMessage() {
        ProbeRunner throwing = new ProbeRunner(List.of(probe("t", context -> {
            throw new IllegalArgumentException("http://user:pw@host");
        })), SECOND);
        ProbeRunner silent = new ProbeRunner(List.of(probe("s", context -> null)), SECOND);

        assertEquals(throwing.check(new ProbeContext(application(true))),
                ProbeResult.fail("t: the probe threw java.lang.IllegalArgumentException"));
        assertEquals(silent.check(new ProbeContext(application(true))),
                ProbeResult.fail("s: the probe returned no result"));
    }

    @Test
    public void theIntervalMustBePositive() {
        expectThrows(IllegalArgumentException.class, () -> new ProbeRunner(List.of(), Duration.ZERO));
        expectThrows(IllegalArgumentException.class, () -> new ProbeRunner(List.of(), Duration.ofSeconds(-1)));
        assertEquals(new ProbeRunner(List.of(), SECOND).interval(), SECOND);
    }

    @Test
    public void theRegistryBuildsTheProcessProbeAndRefusesUnknownTypes() throws Exception {
        ProbeRegistry registry = ProbeRegistry.defaults();
        ProbeConfig config = defaultProbeConfig();

        List<ProbeRegistry.NamedProbe> probes = registry.create(config.readinessTypes(), config);

        assertEquals(registry.types(), Set.of(ProcessProbe.TYPE));
        assertEquals(probes.size(), 1);
        assertEquals(probes.get(0).type(), ProcessProbe.TYPE);
        assertEquals(probes.get(0).probe().getClass(), ProcessProbe.class);
        IllegalArgumentException unknown = expectThrows(IllegalArgumentException.class,
                () -> registry.create(List.of("tcp"), config));
        assertEquals(unknown.getMessage(), "unknown probe type tcp; known: [process]");
    }

    private static ProbeConfig defaultProbeConfig() throws Exception {
        Path dir = Files.createTempDirectory("nyajsw-probe");
        try {
            Path conf = Files.writeString(dir.resolve("wrapper.conf"), "wrapper.java.app.mainclass=Main\n");
            return WrapperConfig.from(new ConfigLoader(Map.of(), Map.of(), false, ConfigKeys.registry()).load(conf))
                    .probes();
        } finally {
            try (var paths = Files.walk(dir)) {
                for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
