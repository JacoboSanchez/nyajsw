package io.github.jacobosanchez.nyajsw.launch;

import io.github.jacobosanchez.nyajsw.agentlink.AgentChannel;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * One started run of the application, as the supervisor sees it. {@link ApplicationProcess} is the
 * real one; the interface exists so that supervision can be tested with fake processes and a fake
 * clock (plan P3).
 */
public interface RunningApplication {

    /** {@code jvm N}, the name its lines are logged under. */
    String name();

    /** The operating system's process id. */
    long pid();

    /** Whether the process is still running. */
    boolean isAlive();

    /**
     * Completes with the exit code once the process has ended and its output is logged. Each call
     * may return a new future; completing or cancelling it does not affect the process. A future
     * that completes exceptionally says nothing about the process, which may still be alive.
     */
    CompletableFuture<Integer> onExit();

    /**
     * Completes as soon as the process has ended, before its output is drained and before {@link
     * #onExit()}, which may come up to a couple of seconds later when a process it started holds the
     * output open. It tells how long the process ran; the exit code and the end of its output come
     * through {@link #onExit()}. By default the same moment as {@link #onExit()}, for runs that
     * cannot tell the two apart.
     */
    default CompletableFuture<Void> onProcessEnd() {
        return onExit().handle((code, error) -> null);
    }

    /** Kills the process and everything it started, at once, without waiting for them to end. */
    void terminateTree();

    /**
     * Its recent output lines, as logged, for probes that look for a line. The same object on every
     * call, so that a wrapper that forgets to pass it on is noticed rather than showing no output.
     */
    OutputLines output();

    /** The session with its agent, once the agent has connected and while the session is open (plan §4.7). */
    Optional<AgentChannel> agent();
}
