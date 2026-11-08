package io.github.jacobosanchez.nyajsw.probe;

import io.github.jacobosanchez.nyajsw.config.ConfigKeys;
import io.github.jacobosanchez.nyajsw.config.ProbeConfig;
import io.github.jacobosanchez.nyajsw.launch.ProcessTrees;
import io.github.jacobosanchez.nyajsw.log.Durations;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * The {@code command} probe: it runs {@code wrapper.readiness.command} and passes when it exits with
 * 0 within {@code wrapper.readiness.command.timeout}. The command runs without a shell (AGENTS.md
 * §4.7), as an argument array, in the application's working directory and with the wrapper's
 * environment; its output is discarded and its input closed at once, so that it cannot wait for
 * either. One that overruns is killed with what it started. Windows runs a {@code .cmd} or {@code
 * .bat} program through {@code cmd.exe}, which expands {@code %VARIABLE%} in its arguments.
 *
 * <p>Log messages name the program only, not its arguments, which can carry credentials.
 */
public final class CommandProbe implements Probe {

    /** The type name the configuration uses. */
    public static final String TYPE = "command";

    private final List<String> command;
    private final String program;
    private final Path directory;
    private final Duration timeout;

    /**
     * @param command   the program and its arguments, at least the program
     * @param directory where it runs
     * @param timeout   how long one run may take, at least 1 ms
     */
    CommandProbe(List<String> command, Path directory, Duration timeout) {
        this.command = List.copyOf(command);
        if (this.command.isEmpty()) {
            throw new IllegalArgumentException("no program to run");
        }
        this.program = this.command.get(0);
        this.directory = Objects.requireNonNull(directory, "directory");
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
        Process process;
        try {
            process = new ProcessBuilder(command).directory(directory.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            // The JDK's reason ("CreateProcess error=2, ...") names the program and the directory,
            // never the arguments, which can carry credentials.
            String reason = e.getCause() != null && e.getCause().getMessage() != null ? e.getCause().getMessage()
                    : e.getMessage();
            return ProbeResult.fail("cannot run " + program + ": " + reason);
        } catch (RuntimeException e) {
            // Thrown for arguments that cannot be passed (a NUL character); its message could quote them.
            return ProbeResult.fail("cannot run " + program + ": " + e.getClass().getSimpleName());
        }
        try {
            process.getOutputStream().close();
        } catch (IOException e) {
            // It may have ended already; its input is closed either way.
        }
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                kill(process);
                return ProbeResult.fail(program + " did not end within " + Durations.seconds(timeout) + "; killed");
            }
        } catch (InterruptedException e) {
            kill(process);
            Thread.currentThread().interrupt();
            return ProbeResult.fail(program + " was interrupted; killed");
        }
        int code = process.exitValue();
        return code == 0 ? ProbeResult.pass(program + " exited with 0")
                : ProbeResult.fail(program + " exited with " + code);
    }

    private static void kill(Process process) {
        ProcessTrees.killTree(process.toHandle());
    }
}
