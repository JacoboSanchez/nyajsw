package io.jsl.nyajsw.probe;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Checks a set of probes that must all pass (plan §4.7: {@code wrapper.readiness.type} or {@code
 * wrapper.liveness.type} lists them), and knows how often to repeat the check. When to check is the
 * supervisor's business: it polls readiness at {@link #interval()} until the check passes or {@code
 * wrapper.startup.timeout} runs out, and liveness at its interval while the application runs. Immutable.
 */
public final class ProbeRunner {

    private final List<ProbeRegistry.NamedProbe> probes;
    private final Duration interval;
    private final UnaryOperator<String> mask;

    /** As {@link #ProbeRunner(List, Duration, UnaryOperator)}, for probes whose details hold no secret. */
    public ProbeRunner(List<ProbeRegistry.NamedProbe> probes, Duration interval) {
        this(probes, interval, UnaryOperator.identity());
    }

    /**
     * @param probes   the probes, checked in this order; none means always ready
     * @param interval the pause between two checks, positive
     * @param mask     hides secrets in a failure's detail, which is logged: a probe's settings may hold
     *                 a password copied in with {@code ${...}} (the configuration's {@code SecretMasker})
     */
    public ProbeRunner(List<ProbeRegistry.NamedProbe> probes, Duration interval, UnaryOperator<String> mask) {
        this.probes = List.copyOf(probes);
        this.mask = Objects.requireNonNull(mask, "mask");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("the probe interval must be positive: " + interval);
        }
    }

    /** The pause between two checks. */
    public Duration interval() {
        return interval;
    }

    /** Whether there is no probe, so that every check passes. */
    public boolean isEmpty() {
        return probes.isEmpty();
    }

    /** The probe type names, in order, for log messages. */
    public String types() {
        return probes.stream().map(ProbeRegistry.NamedProbe::type).collect(Collectors.joining(","));
    }

    /**
     * Tells every probe that an invocation is about to be launched ({@link Probe#beforeLaunch}); one
     * that throws is passed over.
     */
    public void beforeLaunch() {
        for (ProbeRegistry.NamedProbe named : probes) {
            try {
                named.probe().beforeLaunch();
            } catch (RuntimeException e) {
                // The probe's own business; its checks will tell what it lacks.
            }
        }
    }

    /**
     * Checks the probes in order. After the first that does not pass, only those that {@linkplain
     * Probe#readsEveryCheck read on every check} are checked still (the {@code log} probe, which would
     * otherwise fall behind); the others are not, so a failing probe keeps the round short and gates
     * the ones after it. An interrupt (the wrapper closing) ends the round. A probe that throws, or
     * returns nothing, fails; its exception is named by class only, since its message may quote a
     * configured value.
     *
     * @return a pass when every probe passed, else the failures of the probes checked, each detail
     *         prefixed with its type, joined with {@code "; "}, secrets masked
     */
    public ProbeResult check(ProbeContext context) {
        List<String> failures = new ArrayList<>();
        for (ProbeRegistry.NamedProbe named : probes) {
            if (Thread.currentThread().isInterrupted()) {
                if (failures.isEmpty()) {
                    failures.add("the check was interrupted");
                }
                break;
            }
            if (!failures.isEmpty() && !named.probe().readsEveryCheck()) {
                continue;
            }
            ProbeResult result;
            try {
                result = named.probe().check(context);
                if (result == null) {
                    result = ProbeResult.fail("the probe returned no result");
                }
            } catch (RuntimeException e) {
                result = ProbeResult.fail("the probe threw " + e.getClass().getName());
            }
            if (!result.passed()) {
                failures.add(named.type() + ": " + result.detail());
            }
        }
        return failures.isEmpty() ? ProbeResult.pass(probes.isEmpty() ? "no probes" : types())
                : ProbeResult.fail(mask.apply(String.join("; ", failures)));
    }
}
