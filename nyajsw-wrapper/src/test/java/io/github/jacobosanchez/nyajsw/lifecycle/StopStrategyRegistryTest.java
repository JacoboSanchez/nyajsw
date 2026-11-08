package io.github.jacobosanchez.nyajsw.lifecycle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import io.github.jacobosanchez.nyajsw.TestConfigs;
import io.github.jacobosanchez.nyajsw.config.ControlMode;
import io.github.jacobosanchez.nyajsw.config.ExitAction;
import io.github.jacobosanchez.nyajsw.config.LogLevel;
import io.github.jacobosanchez.nyajsw.config.WrapperConfig;
import io.github.jacobosanchez.nyajsw.log.WrapperLog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class StopStrategyRegistryTest {

    private Path dir;

    @BeforeMethod
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("nyajsw-stop");
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws IOException {
        TestConfigs.delete(dir);
    }

    /** The chain of a configuration file that sets {@code lines}, with its own secrets. */
    private StopChain chainOf(String... lines) throws IOException {
        WrapperConfig config = TestConfigs.load(dir, lines);
        return StopStrategyRegistry.defaults().chain(config.lifecycle(), config.secrets());
    }

    private StopChain chain(String names) throws IOException {
        return chainOf("wrapper.stop.strategy=" + names);
    }

    private static List<String> gracefulNames(StopChain chain) {
        return chain.graceful().stream().map(StopChain.Step::name).toList();
    }

    @Test
    public void theDefaultChainAsksTheAgentThenTerminates() throws IOException {
        StopChain chain = chainOf();

        assertEquals(gracefulNames(chain), List.of(AgentStopStrategy.NAME));
        assertEquals(chain.last().name(), TerminateStopStrategy.NAME);
        assertTrue(chain.last().strategy() instanceof TerminateStopStrategy);
        assertEquals(chain.warnings(), List.of());
        assertEquals(StopStrategyRegistry.defaults().names(), Set.of("agent", "terminate"));
    }

    @Test
    public void namesIgnoreCaseAndSurroundingBlanks() throws IOException {
        assertEquals(chain(" Terminate ").warnings(), List.of());
    }

    @Test
    public void anUnknownStrategyIsSkippedWithAWarning() throws IOException {
        StopChain chain = chain("telepathy,terminate");

        assertEquals(gracefulNames(chain), List.of());
        assertEquals(chain.warnings(), List.of(
                "wrapper.stop.strategy: unknown stop strategy telepathy, skipped; known: [agent, terminate]"));
    }

    @Test
    public void aListThatHoldsASecretIsIgnoredWithoutEchoingIt() throws IOException {
        for (String password : List.of("Hunter2-Secret", "p@ss,w0rd", "qx7 , zk9")) {
            WrapperConfig config = TestConfigs.load(dir, "wrapper.ntservice.password=" + password.replace(",", "\\,"),
                    "wrapper.stop.strategy=${wrapper.ntservice.password},terminate");
            StopChain chain = StopStrategyRegistry.defaults().chain(config.lifecycle(), config.secrets());

            String warnings = String.join("\n", chain.warnings()) + "\n"
                    + config.warnings().stream().map(Object::toString).reduce("", (a, b) -> a + "\n" + b);
            assertEquals(gracefulNames(chain), List.of(AgentStopStrategy.NAME), password);
            assertEquals(chain.warnings(), List.of(), "the default list applies: " + warnings);
            for (String piece : password.split(",")) {
                assertFalse(warnings.toLowerCase().contains(piece.strip().toLowerCase()), password + ": " + warnings);
            }
            assertTrue(warnings.contains("wrapper.stop.strategy contains a secret, which cannot name a stop strategy"),
                    warnings);
        }
    }

    @Test
    public void aConfiguredNameIsMaskedInTheWarningsAsWritten() throws IOException {
        // The list itself holds no secret here, but a name that equals one is still masked.
        WrapperConfig config = TestConfigs.load(dir, "wrapper.ntservice.password=Telepathy");
        StopChain chain = StopStrategyRegistry.defaults().chain(Lifecycles.lifecycle(ControlMode.TIGHT,
                ExitAction.SHUTDOWN, Map.of(), Lifecycles.MAX_FAILED, Duration.ZERO, Lifecycles.STARTUP_TIMEOUT,
                Duration.ZERO, List.of("Telepathy", "terminate")), config.secrets());

        assertEquals(chain.warnings(), List.of(
                "wrapper.stop.strategy: unknown stop strategy ****, skipped; known: [agent, terminate]"));
    }

    @Test
    public void terminateIsAddedWhenMissing() throws IOException {
        StopChain chain = chain("telepathy");

        assertEquals(chain.last().name(), TerminateStopStrategy.NAME);
        assertEquals(chain.warnings().get(1),
                "wrapper.stop.strategy does not end with terminate; it is added as the last step");
    }

    @Test
    public void repetitionsAreIgnoredWithAWarning() throws IOException {
        StopChain chain = chain("terminate,terminate");

        assertEquals(chain.warnings(), List.of(
                "wrapper.stop.strategy lists terminate more than once; the repetition is ignored"));
    }

    @Test
    public void onlySkippedNamesAfterTerminateDoNotCountAsMovingIt() throws IOException {
        StopChain chain = chain("terminate,telepathy,terminate");

        assertFalse(chain.warnings().stream().anyMatch(warning -> warning.contains("always the last step")),
                chain.warnings().toString());
    }

    @Test
    public void theAgentStrategyComesBeforeTerminate() throws IOException {
        StopChain chain = chain("agent, terminate");

        assertEquals(gracefulNames(chain), List.of(AgentStopStrategy.NAME));
        assertTrue(chain.graceful().get(0).strategy() instanceof AgentStopStrategy);
        assertEquals(chain.warnings(), List.of());
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
