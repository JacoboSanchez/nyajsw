package io.github.jacobosanchez.nyajsw.platform.windows;

import io.github.jacobosanchez.nyajsw.platform.PlatformException;

/** What to tell the operator when JNA's native library cannot be loaded (plan §4.4, R3). */
final class NativeLibrary {

    private NativeLibrary() {
    }

    /**
     * The failure to {@code what} because of {@code e} ({@code UnsatisfiedLinkError}, {@code
     * NoClassDefFoundError}, {@code ExceptionInInitializerError}): JNA unpacks its library into
     * {@code jna.tmpdir} first, so the message names that directory and the fallback.
     */
    static PlatformException cannotLoad(String what, LinkageError e) {
        return new PlatformException("cannot " + what + ": the native library (JNA) cannot be loaded: " + e
                + "; check that the directory '" + System.getProperty("jna.tmpdir", System.getProperty(
                        "java.io.tmpdir")) + "' (jna.tmpdir, from wrapper.tmp.path) is writable, or ship"
                + " jnidispatch.dll and set jna.boot.library.path", e);
    }
}
