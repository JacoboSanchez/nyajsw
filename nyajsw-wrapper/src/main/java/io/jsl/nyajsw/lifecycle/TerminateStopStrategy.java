package io.jsl.nyajsw.lifecycle;

import io.jsl.nyajsw.launch.RunningApplication;

/**
 * The {@code terminate} strategy, always the last step of a stop: kills the application and every
 * process it started at once ({@link RunningApplication#terminateTree()}), since on Windows {@code
 * Process.destroy()} is already a hard kill that leaves grandchildren running.
 */
public final class TerminateStopStrategy implements StopStrategy {

    /** The name {@code wrapper.stop.strategy} uses. */
    public static final String NAME = "terminate";

    @Override
    public Result requestStop(Context context) {
        context.application().terminateTree();
        return Result.REQUESTED;
    }
}
