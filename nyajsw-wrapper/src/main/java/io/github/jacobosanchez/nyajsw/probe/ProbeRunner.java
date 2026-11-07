package io.github.jacobosanchez.nyajsw.probe;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Checks a set of probes that must all pass (plan §4.7: {@code wrapper.readiness.type} lists them),
 * and knows how often to repeat the check. When to check is the supervisor's business, which polls
 * at {@link #interval()} until the check passes or {@code wrapper.startup.timeout} runs out. Immutable.
 */
public final class ProbeRunner {

    private final List<ProbeRegistry.NamedProbe> probes;
    private final Duration interval;

    /**
     * @param probes   the probes, checked in this order; none means always ready
     * @param interval the pause between two checks, positive
     */
    public ProbeRunner(List<ProbeRegistry.NamedProbe> probes, Duration interval) {
        this.probes = List.copyOf(probes);
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("the probe interval must be positive: " + interval);
        }
    }

    /** The pause between two checks. */
    public Duration interval() {
        return interval;
    }

    /** The probe type names, in order, for log messages. */
    public String types() {
        return probes.stream().map(ProbeRegistry.NamedProbe::type).collect(Collectors.joining(","));
    }

    /**
     * Checks the probes in order and stops at the first that does not pass. A probe that throws, or
     * returns nothing, fails; its exception is named by class only, since its message may quote a
     * configured value.
     *
     * @return a pass when every probe passed, else the first failure, its detail prefixed with the type
     */
    public ProbeResult check(ProbeContext context) {
        for (ProbeRegistry.NamedProbe named : probes) {
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
                return ProbeResult.fail(named.type() + ": " + result.detail());
            }
        }
        return ProbeResult.pass(probes.isEmpty() ? "no probes" : types());
    }
}
