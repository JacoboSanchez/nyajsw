package io.jsl.nyajsw.agentlink;

/**
 * Where an invocation stands with its agent, so that a check can tell "there is no agent to ask"
 * from "the agent does not answer".
 */
public enum AgentState {

    /**
     * The invocation runs without the agent: it was not injected (disabled, {@code wrapper.image},
     * no jar, no port), or it did not connect within the listener's window.
     */
    NONE,

    /** The agent was injected and has not connected yet. */
    WAITING,

    /** The agent has connected and its session is open. */
    CONNECTED,

    /** The agent had connected and its session ended: the application JVM or the agent is gone. */
    LOST
}
