package io.github.jacobosanchez.nyajsw.platform;

import io.github.jacobosanchez.nyajsw.platform.unsupported.UnsupportedPlatform;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Chooses the {@link Platform} for a host by its {@code os.name}: the first registered platform
 * that claims the name, else {@link UnsupportedPlatform}, which runs the console mode only. A new
 * platform is a new entry in {@link #REGISTERED}, not a test spread over the code.
 */
public final class Platforms {

    /**
     * A platform and the {@code os.name} values it claims.
     *
     * @param claims  whether the platform runs on a host of this {@code os.name}
     * @param factory builds the platform for that {@code os.name}
     */
    record Entry(Predicate<String> claims, Function<String, Platform> factory) {

        Entry {
            Objects.requireNonNull(claims, "claims");
            Objects.requireNonNull(factory, "factory");
        }
    }

    /** The platforms nyajsw implements, tried in order; Windows joins with its service controller (P5.2). */
    private static final List<Entry> REGISTERED = List.of();

    private Platforms() {
    }

    /** The platform of the host this JVM runs on. */
    public static Platform forThisHost() {
        return forOsName(System.getProperty("os.name", ""));
    }

    /** The platform for a host whose {@code os.name} system property is {@code osName}. */
    static Platform forOsName(String osName) {
        return forOsName(osName, REGISTERED);
    }

    static Platform forOsName(String osName, List<Entry> entries) {
        Objects.requireNonNull(osName, "osName");
        return entries.stream().filter(entry -> entry.claims().test(osName)).findFirst()
                .map(entry -> entry.factory().apply(osName))
                .orElseGet(() -> new UnsupportedPlatform(osName));
    }
}
