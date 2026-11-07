package io.github.jacobosanchez.nyajsw.lifecycle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import io.github.jacobosanchez.nyajsw.agentlink.AgentListener;
import io.github.jacobosanchez.nyajsw.agentlink.FakeAgent;
import io.github.jacobosanchez.nyajsw.config.LogLevel;
import io.github.jacobosanchez.nyajsw.log.WrapperLog;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class AgentStopStrategyTest {

    private ByteArrayOutputStream logBytes;
    private WrapperLog log;

    @BeforeMethod
    public void setUp() {
        logBytes = new ByteArrayOutputStream();
        log = WrapperLog.console(new PrintStream(logBytes, true, StandardCharsets.UTF_8), LogLevel.INFO);
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        log.close();
    }

    @Test
    public void withoutAConnectedAgentItDoesNotApply() {
        StopStrategy.Result result = new AgentStopStrategy().requestStop(new StopStrategy.Context(
                new FakeApplication(1), log, Duration.ofSeconds(10)));

        assertEquals(result, StopStrategy.Result.NOT_APPLICABLE);
    }

    @Test(timeOut = 60_000)
    public void itAsksTheAgentWithoutWaitingForTheAnswer() throws Exception {
        try (AgentListener listener = AgentListener.open("jvm 1", log, new SecureRandom());
             FakeAgent agent = FakeAgent.introduce(listener)) {
            FakeApplication application = new FakeApplication(1);
            application.connectAgent(listener.channel().orElseThrow());
            agent.holdAnswers(); // the strategy must return before the agent answers

            // A share longer than the test may take: a strategy that waited for the answer would time out.
            StopStrategy.Result result = new AgentStopStrategy().requestStop(new StopStrategy.Context(application,
                    log, Duration.ofMinutes(10)));

            assertEquals(result, StopStrategy.Result.REQUESTED);
            agent.resume();
            assertTrue(agent.awaitStop(30));
            awaitLogged("the agent of jvm 1 is ending the application");
        }
    }

    @Test(timeOut = 60_000)
    public void anAgentThatDoesNotAcknowledgeInItsShareIsLogged() throws Exception {
        try (AgentListener listener = AgentListener.open("jvm 1", log, new SecureRandom());
             FakeAgent agent = FakeAgent.introduce(listener)) {
            FakeApplication application = new FakeApplication(1);
            application.connectAgent(listener.channel().orElseThrow());
            agent.holdAnswers();

            new AgentStopStrategy().requestStop(new StopStrategy.Context(application, log, Duration.ofMillis(200)));

            awaitLogged("the agent of jvm 1 did not acknowledge the stop within 0.200 s");
        }
    }

    private void awaitLogged(String text) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!logBytes.toString(StandardCharsets.UTF_8).contains(text)) {
            assertTrue(System.nanoTime() < deadline, "no '" + text + "' in " + logBytes);
            Thread.sleep(10); // the strategy's thread logs; nothing to wait on
        }
    }
}
