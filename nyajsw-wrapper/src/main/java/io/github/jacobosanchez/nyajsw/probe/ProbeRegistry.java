package io.github.jacobosanchez.nyajsw.probe;

import io.github.jacobosanchez.nyajsw.config.ProbeConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * The probe types by name (plan §4.7, D15). Each entry builds a probe from the configuration, so
 * adding a type means adding a class and one entry here, not editing the supervisor. Immutable.
 */
public final class ProbeRegistry {

    private final Map<String, Function<ProbeConfig, Probe>> factories;

    private ProbeRegistry(Map<String, Function<ProbeConfig, Probe>> factories) {
        this.factories = Collections.unmodifiableMap(new LinkedHashMap<>(factories));
    }

    /** The probe types nyajsw implements; the others of §4.7 are added in P7. */
    public static ProbeRegistry defaults() {
        Map<String, Function<ProbeConfig, Probe>> factories = new LinkedHashMap<>();
        factories.put(ProcessProbe.TYPE, config -> new ProcessProbe());
        return new ProbeRegistry(factories);
    }

    /** The registered type names, in registration order. */
    public Set<String> types() {
        return factories.keySet();
    }

    /**
     * Builds the probes named by {@code types}, in that order.
     *
     * @throws IllegalArgumentException for a type that is not registered
     */
    public List<NamedProbe> create(List<String> types, ProbeConfig config) {
        Objects.requireNonNull(config, "config");
        List<NamedProbe> probes = new ArrayList<>();
        for (String type : types) {
            Function<ProbeConfig, Probe> factory = factories.get(type);
            if (factory == null) {
                throw new IllegalArgumentException("unknown probe type " + type + "; known: " + types());
            }
            probes.add(new NamedProbe(type, factory.apply(config)));
        }
        return List.copyOf(probes);
    }

    /**
     * A probe with the type name it was created under, for log messages.
     *
     * @param type  the type name
     * @param probe the probe
     */
    public record NamedProbe(String type, Probe probe) {

        public NamedProbe {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(probe, "probe");
        }
    }
}
