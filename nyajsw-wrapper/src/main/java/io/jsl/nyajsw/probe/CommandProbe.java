package io.jsl.nyajsw.probe;

import io.jsl.nyajsw.config.ConfigKeys;
import io.jsl.nyajsw.config.ProbeConfig;
import io.jsl.nyajsw.launch.ExternalCommand;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * The {@code command} probe: it runs {@code wrapper.readiness.command} and passes when it exits with
 * 0 within {@code wrapper.readiness.command.timeout}. The command runs in the application's working
 * directory as an {@link ExternalCommand}: without a shell, with the wrapper's environment, its
 * output discarded and its input closed; one that overruns is killed with what it started.
 *
 * <p>Log messages name the program only, not its arguments, which can carry credentials.
 */
public final class CommandProbe implements Probe {

    /** The type name the configuration uses. */
    public static final String TYPE = "command";

    private final ExternalCommand command;
    private final Duration timeout;

    /**
     * @param command   the program and its arguments, at least the program
     * @param directory where it runs
     * @param timeout   how long one run may take, at least 1 ms
     */
    CommandProbe(List<String> command, Path directory, Duration timeout) {
        this.command = new ExternalCommand(command, directory);
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.toMillis() < 1) {
            throw new IllegalArgumentException("not a timeout: " + timeout);
        }
    }

    /**
     * The probe {@code config} describes.
     *
     * @throws IllegalArgumentException when {@code wrapper.readiness.command} is not set
     */
    static CommandProbe from(ProbeConfig config) {
        if (config.command().isEmpty()) {
            throw new IllegalArgumentException("the " + TYPE + " probe needs " + ConfigKeys.READINESS_COMMAND.name());
        }
        return new CommandProbe(config.command(), config.workingDir(), config.commandTimeout());
    }

    @Override
    public ProbeResult check(ProbeContext context) {
        ExternalCommand.Outcome outcome = command.run(timeout);
        String detail = ExternalCommand.describe(outcome, command.program(), timeout);
        return outcome.equals(new ExternalCommand.Exited(0)) ? ProbeResult.pass(detail) : ProbeResult.fail(detail);
    }
}
