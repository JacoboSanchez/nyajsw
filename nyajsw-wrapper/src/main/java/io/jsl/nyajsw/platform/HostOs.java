package io.jsl.nyajsw.platform;

import java.util.Locale;

/**
 * Whether the wrapper runs on Windows, decided in one place: for the few launch and configuration
 * details that differ by host, and for the Windows entry of {@link Platforms}.
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
