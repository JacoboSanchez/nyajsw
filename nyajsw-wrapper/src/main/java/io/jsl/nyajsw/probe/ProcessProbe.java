package io.jsl.nyajsw.probe;

/**
 * The {@code process} probe, the default: the application is ready as soon as its process runs,
 * which is all YAJSW checks before it reports a service as started.
 */
public final class ProcessProbe implements Probe {

    /** The type name the configuration uses. */
    public static final String TYPE = "process";

    @Override
    public ProbeResult check(ProbeContext context) {
        String name = context.application().name();
        return context.application().isAlive()
                ? ProbeResult.pass(name + " is running")
                : ProbeResult.fail(name + " is not running");
    }
}
