package io.jsl.nyajsw.lifecycle;

import io.jsl.nyajsw.agentlink.AgentChannel;
import io.jsl.nyajsw.log.Durations;

import java.util.Optional;

/**
 * The {@code agent} stop strategy (plan §4.6): it asks the invocation's agent to end the
 * application JVM with {@code System.exit(0)}, so that the application's shutdown hooks run, then
 * leaves the waiting to the supervisor. Without a connected agent it does not apply, and the next
 * step follows at once. The request goes out on a thread of its own, since the supervisor's thread
 * must not block on the socket.
 */
public final class AgentStopStrategy implements StopStrategy {

    /** The name the configuration uses. */
    public static final String NAME = "agent";

    @Override
    public Result requestStop(Context context) {
        Optional<AgentChannel> agent = context.application().agent();
        if (agent.isEmpty()) {
            return Result.NOT_APPLICABLE;
        }
        String name = context.application().name();
        AgentChannel channel = agent.get();
        Thread.ofVirtual().name("nyajsw-agent-stop").start(() -> {
            if (channel.stop(context.share())) {
                context.log().status("the agent of " + name + " is ending the application");
            } else if (channel.isOpen()) {
                context.log().status("the agent of " + name + " did not acknowledge the stop within "
                        + Durations.seconds(context.share()));
            }
            // A session that ended: the application is ending, or the agent with it; the supervisor sees which.
        });
        return Result.REQUESTED;
    }
}
