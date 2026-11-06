package io.github.jacobosanchez.nyajsw.lifecycle;

import io.github.jacobosanchez.nyajsw.launch.RunningApplication;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** A {@link RunningApplication} that exits when a test says so. */
final class FakeApplication implements RunningApplication {

    private final String name;
    private final long pid;
    private final CompletableFuture<Integer> exit = new CompletableFuture<>();
    private final AtomicInteger terminations = new AtomicInteger();
    private volatile boolean alive = true;
    /** The exit code a termination ends it with; {@code null} for an application that survives it. */
    private volatile Integer codeOnTermination = 1;
    private volatile boolean failingTermination;
    private volatile boolean failingOnExit;

    FakeApplication(int invocation) {
        this.name = "jvm " + invocation;
        this.pid = 1000 + invocation;
    }

    /** Ends the application with {@code code}. */
    void exit(int code) {
        alive = false;
        exit.complete(code);
    }

    /** Makes the observed exit fail while the process stays alive, as if the wrapper lost track of it. */
    void loseTrack(Throwable error) {
        exit.completeExceptionally(error);
    }

    /** Makes {@link #terminateTree()} leave the application running. */
    FakeApplication survivingTermination() {
        codeOnTermination = null;
        return this;
    }

    /** Makes {@link #terminateTree()} throw, after counting the call, and leave the application running. */
    FakeApplication failingTermination() {
        failingTermination = true;
        return survivingTermination();
    }

    /** Makes {@link #onExit()} throw. */
    FakeApplication failingOnExit() {
        failingOnExit = true;
        return this;
    }

    int terminations() {
        return terminations.get();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public long pid() {
        return pid;
    }

    @Override
    public boolean isAlive() {
        return alive;
    }

    @Override
    public CompletableFuture<Integer> onExit() {
        if (failingOnExit) {
            throw new IllegalStateException("onExit broke");
        }
        return exit.copy();
    }

    @Override
    public void terminateTree() {
        terminations.incrementAndGet();
        if (failingTermination) {
            throw new IllegalStateException("terminateTree broke");
        }
        Integer code = codeOnTermination;
        if (code != null) {
            exit(code);
        }
    }
}
