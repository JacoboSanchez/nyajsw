package io.github.jacobosanchez.nyajsw.lifecycle;

import io.github.jacobosanchez.nyajsw.config.FailureAction;
import io.github.jacobosanchez.nyajsw.probe.ProbeRunner;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * How the {@link Supervisor} watches an application that is running (plan §4.7): the probes of
 * {@code wrapper.liveness.type}, checked every {@code wrapper.liveness.interval}, and what it does
 * after {@code wrapper.liveness.failures} failed checks in a row ({@code wrapper.liveness.action}).
 * Without probes nothing is checked, and only the exit of the application is observed.
 *
 * @param probes   the probes and the pause between two checks
 * @param failures failed checks in a row that trigger {@code action}, at least 1
 * @param action   what follows them
 */
public record Liveness(ProbeRunner probes, int failures, FailureAction action) {

    /** No liveness checks. */
    public static final Liveness NONE = new Liveness(new ProbeRunner(List.of(), Duration.ofSeconds(10)), 1,
            FailureAction.RESTART);

    public Liveness {
        Objects.requireNonNull(probes, "probes");
        Objects.requireNonNull(action, "action");
        if (failures < 1) {
            throw new IllegalArgumentException("at least one failed check must trigger the action: " + failures);
        }
    }

    /** Whether anything is checked. */
    public boolean isEnabled() {
        return !probes.isEmpty();
    }
}
