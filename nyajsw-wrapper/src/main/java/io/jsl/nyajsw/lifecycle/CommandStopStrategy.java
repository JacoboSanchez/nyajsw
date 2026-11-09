package io.jsl.nyajsw.lifecycle;

import io.jsl.nyajsw.config.ConfigKeys;
import io.jsl.nyajsw.config.LifecycleConfig;
import io.jsl.nyajsw.launch.ExternalCommand;
import io.jsl.nyajsw.log.Durations;
import io.jsl.nyajsw.log.WrapperLog;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * The {@code command} stop strategy (plan §4.6): it runs {@code wrapper.stop.command}, such as a
 * {@code .bat} file that calls the application's own shutdown, as an {@link ExternalCommand} in the
 * application's working directory, then leaves the waiting to the supervisor. The command runs on a
 * thread of its own, since the supervisor's thread must not block on it, and is bounded by the
 * strategy's share: one still running when the share runs out is killed with what it started, and
 * so is one still running when the application has ended, since the wrapper may end right after it
 * and would leave the command behind. It asks the application to end; it is not a hook that runs
 * after the stop. Its exit code is logged, nothing else follows from it: the supervisor waits for
 * the application either way.
 *
 * <p>Log messages name the program only, not its arguments, which can carry credentials.
 */
public final class CommandStopStrategy implements StopStrategy {

    /** The name the configuration uses. */
    public static final String NAME = "command";

    private final ExternalCommand command;

    CommandStopStrategy(ExternalCommand command) {
        this.command = Objects.requireNonNull(command, "command");
    }

    /**
     * The strategy {@code config} describes.
     *
     * @throws IllegalArgumentException when {@code wrapper.stop.command} is not set
     */
    static CommandStopStrategy from(LifecycleConfig config) {
        if (config.stopCommand().isEmpty()) {
            throw new IllegalArgumentException(ConfigKeys.STOP_COMMAND.name() + " is not set");
        }
        return new CommandStopStrategy(new ExternalCommand(config.stopCommand(), config.workingDir()));
    }

    @Override
    public Result requestStop(Context context) {
        CompletableFuture<Void> ended = context.application().onProcessEnd();
        Thread.ofVirtual().name("nyajsw-stop-command")
                .start(() -> report(context, command.run(context.share(), ended)));
        return Result.REQUESTED;
    }

    private void report(Context context, ExternalCommand.Outcome outcome) {
        WrapperLog log = context.log();
        String program = "the stop command " + command.program();
        switch (outcome) {
            case ExternalCommand.Exited(int code) when code == 0 -> log.status(program + " exited with 0");
            case ExternalCommand.Exited(int code) -> log.error(program + " exited with " + code);
            case ExternalCommand.NotStarted(String reason) -> log.error("cannot run " + program + ": " + reason);
            case ExternalCommand.TimedOut() -> log.status(program + " did not end within "
                    + Durations.seconds(context.share()) + "; killed");
            case ExternalCommand.Cancelled() -> log.status(program + " was still running when "
                    + context.application().name() + " ended; killed");
            case ExternalCommand.Interrupted() -> log.error(program + " was interrupted; killed");
        }
    }
}
