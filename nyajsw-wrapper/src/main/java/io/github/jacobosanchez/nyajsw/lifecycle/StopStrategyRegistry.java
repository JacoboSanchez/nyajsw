package io.github.jacobosanchez.nyajsw.lifecycle;

import io.github.jacobosanchez.nyajsw.config.ConfigKeys;
import io.github.jacobosanchez.nyajsw.config.LifecycleConfig;
import io.github.jacobosanchez.nyajsw.config.SecretMasker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * The stop strategies by name (plan §4.6, D15). Each entry builds a strategy from the configuration,
 * so adding one ({@code agent} and {@code command} in P7) means a class and an entry here, not an
 * edit of the supervisor. Immutable.
 */
public final class StopStrategyRegistry {

    private final Map<String, Function<LifecycleConfig, StopStrategy>> factories;

    private StopStrategyRegistry(Map<String, Function<LifecycleConfig, StopStrategy>> factories) {
        this.factories = Collections.unmodifiableMap(new LinkedHashMap<>(factories));
    }

    /** The strategies nyajsw implements. */
    public static StopStrategyRegistry defaults() {
        Map<String, Function<LifecycleConfig, StopStrategy>> factories = new LinkedHashMap<>();
        factories.put(TerminateStopStrategy.NAME, config -> new TerminateStopStrategy());
        return new StopStrategyRegistry(factories);
    }

    /** The registered names, in registration order. */
    public Set<String> names() {
        return factories.keySet();
    }

    /**
     * Builds the chain that {@code wrapper.stop.strategy} ({@link LifecycleConfig#stopStrategies()})
     * names, ignoring case. It never fails, since a stop must always be possible: a name that is not
     * registered (such as {@code agent} before P7), is repeated or cannot be built is skipped, and
     * {@code terminate} is moved or added to the end; each with a warning. The warnings show the
     * configured names masked, as written: {@code ${...}} can copy a secret into the list.
     *
     * @param secrets hides secrets in the warnings
     */
    public StopChain chain(LifecycleConfig config, SecretMasker secrets) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(secrets, "secrets");
        String key = ConfigKeys.STOP_STRATEGY.name();
        List<String> warnings = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<StopChain.Step> graceful = new ArrayList<>();
        boolean terminateListed = false;
        boolean movedBeforeTerminate = false;
        for (String listed : config.stopStrategies()) {
            String name = listed.strip().toLowerCase(Locale.ROOT);
            // Masked before lower-casing, which would hide a secret from the masker.
            String shown = secrets.maskText(listed.strip());
            if (!seen.add(name)) {
                warnings.add(key + " lists " + shown + " more than once; the repetition is ignored");
                continue;
            }
            Function<LifecycleConfig, StopStrategy> factory = factories.get(name);
            if (factory == null) {
                warnings.add(key + ": unknown stop strategy " + shown + ", skipped; known: " + names());
                continue;
            }
            if (name.equals(TerminateStopStrategy.NAME)) {
                terminateListed = true;
                continue;
            }
            StopStrategy strategy;
            try {
                strategy = Objects.requireNonNull(factory.apply(config), name);
            } catch (RuntimeException e) {
                warnings.add(key + ": the stop strategy " + name + " cannot be used (" + e.getClass().getSimpleName()
                        + "), skipped");
                continue;
            }
            movedBeforeTerminate |= terminateListed;
            graceful.add(new StopChain.Step(name, strategy));
        }
        if (movedBeforeTerminate) {
            warnings.add(key + ": " + TerminateStopStrategy.NAME
                    + " is always the last step; the strategies after it come first");
        }
        if (!terminateListed) {
            warnings.add(key + " does not end with " + TerminateStopStrategy.NAME + "; it is added as the last step");
        }
        StopChain.Step last = new StopChain.Step(TerminateStopStrategy.NAME, new TerminateStopStrategy());
        return new StopChain(graceful, last, warnings);
    }
}
