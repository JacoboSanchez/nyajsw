package io.github.jacobosanchez.nyajsw.platform;

import java.util.Locale;

/**
 * Which operating system the wrapper runs on, decided in one place. The {@code Platform} factory of
 * plan P5.1 builds on it; until then it only answers whether this is Windows.
 */
public final class HostOs {

    private HostOs() {
    }

    /** Whether this JVM runs on Windows. */
    public static boolean isWindows() {
        return isWindows(System.getProperty("os.name", ""));
    }

    /** Whether {@code osName}, a value of the {@code os.name} system property, names Windows. */
    static boolean isWindows(String osName) {
        return osName.toLowerCase(Locale.ROOT).startsWith("windows");
    }
}
