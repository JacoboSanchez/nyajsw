package io.github.jacobosanchez.nyajsw.launch;

/**
 * Starts one run of the application. The supervisor calls it for every invocation, so a restart
 * starts a fresh process with the next invocation number.
 */
@FunctionalInterface
public interface ApplicationLauncher {

    /**
     * Starts invocation {@code invocation}.
     *
     * @param invocation the number of this run, 1 for the first; its lines are logged as {@code jvm N}
     * @return the started application
     * @throws LaunchException when the application cannot be started; the message is masked
     */
    RunningApplication launch(int invocation);
}
