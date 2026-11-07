package io.github.jacobosanchez.nyajsw.probe;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.expectThrows;

import io.github.jacobosanchez.nyajsw.agentlink.AgentListener;
import io.github.jacobosanchez.nyajsw.agentlink.FakeAgent;
import io.github.jacobosanchez.nyajsw.config.LogLevel;
import io.github.jacobosanchez.nyajsw.log.WrapperLog;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;

import org.testng.annotations.Test;

public class AgentProbeTest {

    @Test
    public void failsUntilTheAgentHasConnected() {
        assertEquals(new AgentProbe(Duration.ofSeconds(1)).check(FakeRunningApplication.running()),
                ProbeResult.fail("the agent of jvm 1 has not connected"));
    }

    @Test(timeOut = 60_000)
    public void passesWhileTheAgentAnswersAndFailsWhenItDoesNotInTime() throws Exception {
        try (WrapperLog log = WrapperLog.console(new PrintStream(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8), LogLevel.INFO);
             AgentListener listener = AgentListener.open("jvm 1", log, new SecureRandom());
             FakeAgent agent = FakeAgent.introduce(listener)) {
            FakeRunningApplication application = new FakeRunningApplication(true);
            application.connectAgent(listener.channel().orElseThrow());
            ProbeContext context = new ProbeContext(application);

            assertEquals(new AgentProbe(Duration.ofSeconds(10)).check(context),
                    ProbeResult.pass("the agent of jvm 1 answers"));
            agent.holdAnswers();
            assertEquals(new AgentProbe(Duration.ofMillis(200)).check(context),
                    ProbeResult.fail("the agent of jvm 1 did not answer within 0.200 s"));
        }
    }

    @Test
    public void theTimeoutMustBePositive() {
        expectThrows(IllegalArgumentException.class, () -> new AgentProbe(Duration.ZERO));
    }
}
