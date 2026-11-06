package io.github.jacobosanchez.nyajsw.lifecycle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import io.github.jacobosanchez.nyajsw.config.LogLevel;
import io.github.jacobosanchez.nyajsw.log.WrapperLog;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.testng.annotations.Test;

public class StopStrategyRegistryTest {

    private static StopChain chain(String... names) {
        return StopStrategyRegistry.defaults().chain(Lifecycles.withStopStrategies(names));
    }

    private static List<String> gracefulNames(StopChain chain) {
        return chain.graceful().stream().map(StopChain.Step::name).toList();
    }

    @Test
    public void theDefaultChainIsTerminateAlone() {
        StopChain chain = chain("terminate");

        assertEquals(gracefulNames(chain), List.of());
        assertEquals(chain.last().name(), TerminateStopStrategy.NAME);
        assertTrue(chain.last().strategy() instanceof TerminateStopStrategy);
        assertEquals(chain.warnings(), List.of());
        assertEquals(StopStrategyRegistry.defaults().names(), Set.of("terminate"));
    }

    @Test
    public void namesIgnoreCaseAndSurroundingBlanks() {
        assertEquals(chain(" Terminate ").warnings(), List.of());
    }

    @Test
    public void anUnknownStrategyIsSkippedWithAWarning() {
        StopChain chain = chain("agent", "terminate");

        assertEquals(gracefulNames(chain), List.of());
        assertEquals(chain.warnings(), List.of(
                "wrapper.stop.strategy: unknown stop strategy agent, skipped; known: [terminate]"));
    }

    @Test
    public void terminateIsAddedWhenMissing() {
        StopChain chain = chain("agent");

        assertEquals(chain.last().name(), TerminateStopStrategy.NAME);
        assertEquals(chain.warnings().get(1),
                "wrapper.stop.strategy does not end with terminate; it is added as the last step");
    }

    @Test
    public void repetitionsAreIgnoredWithAWarning() {
        StopChain chain = chain("terminate", "terminate");

        assertEquals(chain.warnings(), List.of(
                "wrapper.stop.strategy lists terminate more than once; the repetition is ignored"));
    }

    @Test
    public void onlySkippedNamesAfterTerminateDoNotCountAsMovingIt() {
        StopChain chain = chain("terminate", "agent", "terminate");

        assertFalse(chain.warnings().stream().anyMatch(warning -> warning.contains("always the last step")),
                chain.warnings().toString());
    }

    @Test
    public void terminateKillsTheProcessTree() {
        FakeApplication application = new FakeApplication(1);
        try (WrapperLog log = WrapperLog.console(new PrintStream(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8), LogLevel.INFO)) {
            StopStrategy.Result result = new TerminateStopStrategy().requestStop(
                    new StopStrategy.Context(application, log, Duration.ZERO));

            assertEquals(result, StopStrategy.Result.REQUESTED);
        }
        assertEquals(application.terminations(), 1);
        assertFalse(application.isAlive());
    }
}
