package io.github.jacobosanchez.nyajsw.probe;

import io.github.jacobosanchez.nyajsw.agentlink.AgentChannel;
import io.github.jacobosanchez.nyajsw.log.Durations;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@code agent} probe: it passes when the invocation's agent has connected and answers a ping
 * within the timeout. As readiness that means the application JVM is up far enough to run the
 * agent's thread; as liveness it is YAJSW's heartbeat ({@code wrapper.ping.*}, D16): a JVM that
 * hangs, runs out of memory or stops scheduling threads stops answering.
 */
public final class AgentProbe implements Probe {

    /** The type name the configuration uses. */
    public static final String TYPE = "agent";

    /** How long a ping may take when nothing else is configured; YAJSW's {@code wrapper.ping.timeout}. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final Duration timeout;

    /** @param timeout how long the agent may take to answer, positive */
    AgentProbe(Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (!timeout.isPositive()) {
            throw new IllegalArgumentException("not a timeout: " + timeout);
        }
    }

    @Override
    public ProbeResult check(ProbeContext context) {
        String name = context.application().name();
        Optional<AgentChannel> agent = context.application().agent();
        if (agent.isEmpty()) {
            return ProbeResult.fail("the agent of " + name + " has not connected");
        }
        return agent.get().ping(timeout) ? ProbeResult.pass("the agent of " + name + " answers")
                : ProbeResult.fail("the agent of " + name + " did not answer within " + Durations.seconds(timeout));
    }
}
