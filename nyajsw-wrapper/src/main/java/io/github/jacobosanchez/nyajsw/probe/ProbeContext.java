package io.github.jacobosanchez.nyajsw.probe;

import io.github.jacobosanchez.nyajsw.launch.RunningApplication;

import java.util.Objects;

/**
 * What a probe may look at when it checks one invocation. Probes get their settings when they are
 * created ({@link ProbeRegistry}); the context carries what changes from one invocation to the
 * next, the captured output included ({@link RunningApplication#output()}).
 *
 * @param application the invocation being checked
 */
public record ProbeContext(RunningApplication application) {

    public ProbeContext {
        Objects.requireNonNull(application, "application");
    }
}
