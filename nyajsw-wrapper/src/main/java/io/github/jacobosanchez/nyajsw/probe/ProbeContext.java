package io.github.jacobosanchez.nyajsw.probe;

import io.github.jacobosanchez.nyajsw.launch.RunningApplication;

import java.util.Objects;

/**
 * What a probe may look at when it checks one invocation. Probes get their settings when they are
 * created ({@link ProbeRegistry}); the context carries what changes from one invocation to the
 * next, the captured output included ({@link RunningApplication#output()}), and what the check is
 * for, since a probe may judge a state differently for readiness and liveness (an agent that has
 * not connected yet is not ready, but nothing has failed either).
 *
 * @param application the invocation being checked
 * @param purpose     what the check is for
 */
public record ProbeContext(RunningApplication application, Purpose purpose) {

    /** What a check is for. */
    public enum Purpose {

        /** Whether the invocation has become ready ({@code wrapper.readiness.type}). */
        READINESS,

        /** Whether the running invocation is still alive ({@code wrapper.liveness.type}). */
        LIVENESS
    }

    public ProbeContext {
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(purpose, "purpose");
    }
}
