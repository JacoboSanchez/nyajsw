package io.github.jacobosanchez.nyajsw.launch;

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

    /** Kills the process and everything it started, at once, without waiting for them to end. */
    void terminateTree();
}
