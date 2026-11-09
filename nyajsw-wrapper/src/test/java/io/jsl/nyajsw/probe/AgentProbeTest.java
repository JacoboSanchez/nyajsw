package io.jsl.nyajsw.probe;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import io.jsl.nyajsw.agentlink.AgentListener;
import io.jsl.nyajsw.agentlink.AgentState;
import io.jsl.nyajsw.agentlink.FakeAgent;
import io.jsl.nyajsw.config.LogLevel;
import io.jsl.nyajsw.log.WrapperLog;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;

import org.testng.annotations.Test;

public class AgentProbeTest {

    @Test
    public void anInvocationWithoutTheAgentHasNothingToCheck() {
        assertEquals(new AgentProbe(Optional.of(Duration.ofSeconds(1))).check(FakeRunningApplication.running()),
                ProbeResult.pass("jvm 1 runs without the agent; nothing to check"));
    }

    @Test
    public void anAgentThatHasNotConnectedYetIsNotReadyButNotDeadEither() {
        FakeRunningApplication application = new FakeRunningApplication(true);
        application.agentState(AgentState.WAITING);
        AgentProbe probe = new AgentProbe(Optional.of(Duration.ofSeconds(1)));

        assertEquals(probe.check(new ProbeContext(application, ProbeContext.Purpose.READINESS)),
                ProbeResult.fail("the agent of jvm 1 has not connected yet"));
        assertEquals(probe.check(new ProbeContext(application, ProbeContext.Purpose.LIVENESS)),
                ProbeResult.pass("the agent of jvm 1 has not connected yet"));
    }

    @Test
    public void aSessionThatEndedFails() {
        FakeRunningApplication application = new FakeRunningApplication(true);
        application.agentState(AgentState.LOST);

        assertEquals(new AgentProbe(Optional.of(Duration.ofSeconds(1))).check(new ProbeContext(application,
                ProbeContext.Purpose.LIVENESS)), ProbeResult.fail("the session with the agent of jvm 1 has ended"));
    }

    @Test(timeOut = 60_000)
    public void passesWhileTheAgentAnswersAndFailsWhenItDoesNotInTime() throws Exception {
        try (WrapperLog log = WrapperLog.console(new PrintStream(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8), LogLevel.INFO);
             AgentListener listener = AgentListener.open("jvm 1", log, new SecureRandom());
             FakeAgent agent = FakeAgent.introduce(listener)) {
            FakeRunningApplication application = new FakeRunningApplication(true);
            application.connectAgent(listener.channel().orElseThrow());
            ProbeContext context = new ProbeContext(application, ProbeContext.Purpose.READINESS);

            assertEquals(new AgentProbe(Optional.of(Duration.ofSeconds(10))).check(context),
                    ProbeResult.pass("the agent of jvm 1 answers"));
            agent.holdAnswers();
            assertEquals(new AgentProbe(Optional.of(Duration.ofMillis(200))).check(context),
                    ProbeResult.fail("the agent of jvm 1 did not answer within 0.200 s"));
        }
    }

    @Test
    public void theTimeoutMustBePositive() {
        expectThrows(IllegalArgumentException.class, () -> new AgentProbe(Optional.of(Duration.ZERO)));
    }

    @Test(timeOut = 60_000)
    public void withoutATimeoutAnOpenSessionIsEnough() throws Exception {
        try (WrapperLog log = WrapperLog.console(new PrintStream(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8), LogLevel.INFO);
             AgentListener listener = AgentListener.open("jvm 1", log, new SecureRandom());
             FakeAgent agent = FakeAgent.introduce(listener)) {
            FakeRunningApplication application = new FakeRunningApplication(true);
            application.connectAgent(listener.channel().orElseThrow());
            agent.holdAnswers(); // it would not answer a ping

            assertEquals(new AgentProbe(Optional.empty()).check(new ProbeContext(application,
                    ProbeContext.Purpose.LIVENESS)), ProbeResult.pass("the agent of jvm 1 is connected"));
            assertTrue(agent.received().isEmpty(), "no ping sent");
        }
    }
}
