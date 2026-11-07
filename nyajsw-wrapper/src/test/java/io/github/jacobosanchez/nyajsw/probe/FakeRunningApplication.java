package io.github.jacobosanchez.nyajsw.probe;

import io.github.jacobosanchez.nyajsw.agentlink.AgentChannel;
import io.github.jacobosanchez.nyajsw.launch.OutputLines;
import io.github.jacobosanchez.nyajsw.launch.RunningApplication;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** An invocation named {@code jvm 1} that is alive or not, for checking probes against. */
final class FakeRunningApplication implements RunningApplication {

    private final boolean alive;
    private final OutputLines output = new OutputLines();
    private volatile Optional<AgentChannel> agent = Optional.empty();

    FakeRunningApplication(boolean alive) {
        this.alive = alive;
    }

    /** A context for a running {@code jvm 1}. */
    static ProbeContext running() {
        return new ProbeContext(new FakeRunningApplication(true));
    }

    @Override
    public String name() {
        return "jvm 1";
    }

    @Override
    public long pid() {
        return 42;
    }

    @Override
    public boolean isAlive() {
        return alive;
    }

    @Override
    public CompletableFuture<Integer> onExit() {
        return new CompletableFuture<>();
    }

    @Override
    public void terminateTree() {
    }

    @Override
    public OutputLines output() {
        return output;
    }

    /** Lets the application have {@code channel} as its agent's session. */
    void connectAgent(AgentChannel channel) {
        agent = Optional.of(channel);
    }

    @Override
    public Optional<AgentChannel> agent() {
        return agent;
    }
}
