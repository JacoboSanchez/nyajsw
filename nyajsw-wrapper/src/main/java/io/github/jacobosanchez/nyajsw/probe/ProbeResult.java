package io.github.jacobosanchez.nyajsw.probe;

import java.util.Objects;

/**
 * The outcome of one check.
 *
 * @param passed whether the check passed
 * @param detail what was found, for the log; it must not contain configuration values that could
 *               be secret, so probes describe their target in masked form
 */
public record ProbeResult(boolean passed, String detail) {

    public ProbeResult {
        Objects.requireNonNull(detail, "detail");
    }

    public static ProbeResult pass(String detail) {
        return new ProbeResult(true, detail);
    }

    public static ProbeResult fail(String detail) {
        return new ProbeResult(false, detail);
    }
}
