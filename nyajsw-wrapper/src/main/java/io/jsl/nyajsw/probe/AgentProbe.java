package io.jsl.nyajsw.probe;

import io.jsl.nyajsw.agentlink.AgentChannel;
import io.jsl.nyajsw.log.Durations;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@code agent} probe: it passes when the invocation's agent has connected and answers a ping
 * within the timeout ({@code wrapper.ping.timeout}). As readiness that means the application JVM is
 * up far enough to run the agent's thread; as liveness it is YAJSW's heartbeat ({@code
 * wrapper.ping.*}, D16): a JVM that hangs, runs out of memory or stops scheduling threads stops
 * answering, and a session that ended means the JVM or the agent is gone.
 *
 * <p>An invocation that runs without the agent (not injected, or it never connected) has nothing to
 * check, and passes: the wrapper warns about it, and failing would restart a healthy application
 * that merely cannot have an agent. One whose agent has not connected yet is not ready, but has
 * not failed a liveness check either.
 */
public final class AgentProbe implements Probe {

    /** The type name the configuration uses. */
    public static final String TYPE = "agent";

    private final Optional<Duration> timeout;

    /**
     * @param timeout how long the agent may take to answer, positive; empty: no pings, only an
     *                open session counts ({@code wrapper.ping.timeout=0})
     */
    AgentProbe(Optional<Duration> timeout) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isPresent() && !timeout.get().isPositive()) {
            throw new IllegalArgumentException("not a timeout: " + timeout.get());
        }
    }

    @Override
    public ProbeResult check(ProbeContext context) {
        String name = context.application().name();
        return switch (context.application().agentState()) {
            case NONE -> ProbeResult.pass(name + " runs without the agent; nothing to check");
            case WAITING -> context.purpose() == ProbeContext.Purpose.READINESS
                    ? ProbeResult.fail("the agent of " + name + " has not connected yet")
                    : ProbeResult.pass("the agent of " + name + " has not connected yet");
            case LOST -> ProbeResult.fail("the session with the agent of " + name + " has ended");
            case CONNECTED -> connected(name, context.application().agent());
        };
    }

    private ProbeResult connected(String name, Optional<AgentChannel> agent) {
        if (agent.isEmpty()) {
            return ProbeResult.fail("the session with the agent of " + name + " has ended");
        }
        if (timeout.isEmpty()) {
            return ProbeResult.pass("the agent of " + name + " is connected");
        }
        return agent.get().ping(timeout.get()) ? ProbeResult.pass("the agent of " + name + " answers")
                : ProbeResult.fail("the agent of " + name + " did not answer within "
                        + Durations.seconds(timeout.get()));
    }
}
