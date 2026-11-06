package io.github.jacobosanchez.nyajsw.probe;

/**
 * A readiness or liveness check (plan §4.7). Probe types are registered by name in {@link
 * ProbeRegistry}, and the configuration selects them by that name, so a new type is a new class
 * and a registry entry.
 *
 * <p>A check may block for a bounded time of its own (a connection timeout, for example); it runs
 * off the supervisor's thread. It should report problems as a failed {@link ProbeResult} rather
 * than throw; {@link ProbeRunner} turns an exception into a failure anyway.
 *
 * <p>One instance serves every invocation, since probes are built once from the configuration. A
 * probe that keeps state per invocation (output seen since the launch, an agent connection) keys it
 * on {@link ProbeContext#application()}, which is a new object for each invocation.
 */
@FunctionalInterface
public interface Probe {

    /** Checks the application once. */
    ProbeResult check(ProbeContext context);
}
