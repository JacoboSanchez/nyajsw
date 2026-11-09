package io.jsl.nyajsw.launch;

import io.jsl.nyajsw.log.Durations;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A configured program the wrapper runs for itself, beside the application: the {@code command}
 * probe's {@code wrapper.readiness.command} and the {@code command} stop strategy's {@code
 * wrapper.stop.command}. Both get the same mechanics, so that neither can hang the wrapper: the
 * program runs without a shell (AGENTS.md §4.7), as an argument array, in the given directory and
 * with the wrapper's environment (plan D26); its output is discarded and its input closed at once,
 * so that it cannot wait for either; one that overruns its time, or is no longer wanted, is killed
 * with what it started. Windows runs a {@code .cmd} or {@code .bat} program through {@code
 * cmd.exe}, which expands {@code %VARIABLE%} in its arguments.
 *
 * <p>Outcomes name no arguments, which can carry credentials; callers name the {@link #program()}
 * only. Immutable; each {@link #run} starts a new process.
 */
public final class ExternalCommand {

    /** How a run ended. Every outcome but {@link Exited} and {@link NotStarted} killed the process. */
    public sealed interface Outcome permits Exited, NotStarted, TimedOut, Interrupted, Cancelled {
    }

    /** It ended by itself with {@code code}. */
    public record Exited(int code) implements Outcome {
    }

    /** It could not be started; {@code reason} names the program at most, never its arguments. */
    public record NotStarted(String reason) implements Outcome {

        public NotStarted {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** It did not end within its time and was killed. */
    public record TimedOut() implements Outcome {
    }

    /** The waiting thread was interrupted; the process was killed and the interrupt is kept. */
    public record Interrupted() implements Outcome {
    }

    /**
     * It was no longer wanted (the caller's signal completed): killed, or not started when the signal
     * had completed before the run.
     */
    public record Cancelled() implements Outcome {
    }

    private final List<String> arguments;
    private final Path directory;

    /**
     * @param arguments the program and its arguments, at least the program
     * @param directory where it runs
     */
    public ExternalCommand(List<String> arguments, Path directory) {
        this.arguments = List.copyOf(arguments);
        if (this.arguments.isEmpty()) {
            throw new IllegalArgumentException("no program to run");
        }
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    /** The program as configured, for messages: the arguments can carry credentials. */
    public String program() {
        return arguments.get(0);
    }

    /**
     * The message for {@code outcome}, naming the program as {@code name} (such as {@code "the stop
     * command " + program()}): {@code <name> exited with 3}, {@code cannot run <name>: <reason>},
     * {@code <name> did not end within 10 s; killed}, and so on.
     *
     * @param timeout the time the run had, for {@link TimedOut}
     */
    public static String describe(Outcome outcome, String name, Duration timeout) {
        return switch (outcome) {
            case Exited(int code) -> name + " exited with " + code;
            case NotStarted(String reason) -> "cannot run " + name + ": " + reason;
            case TimedOut() -> name + " did not end within " + Durations.seconds(timeout) + "; killed";
            case Interrupted() -> name + " was interrupted; killed";
            case Cancelled() -> name + " was cancelled";
        };
    }

    /** Runs it to its end, for at most {@code timeout}. */
    public Outcome run(Duration timeout) {
        return run(timeout, new CompletableFuture<>());
    }

    /**
     * Runs it to its end, for at most {@code timeout}, or until {@code cancel} completes, normally or
     * not. A {@code cancel} that has completed already means the program is not started at all.
     */
    public Outcome run(Duration timeout, CompletableFuture<?> cancel) {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(cancel, "cancel");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("not a timeout: " + timeout);
        }
        if (cancel.isDone()) {
            return new Cancelled();
        }
        Process process;
        try {
            process = new ProcessBuilder(arguments).directory(directory.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            // The JDK's reason ("CreateProcess error=2, ...") names the program and the directory,
            // never the arguments, which can carry credentials.
            String reason = e.getCause() != null && e.getCause().getMessage() != null ? e.getCause().getMessage()
                    : e.getMessage();
            return new NotStarted(Objects.toString(reason, e.getClass().getSimpleName()));
        } catch (RuntimeException e) {
            // Thrown for arguments that cannot be passed (a NUL character); its message could quote them.
            return new NotStarted(e.getClass().getSimpleName());
        }
        try {
            process.getOutputStream().close();
        } catch (IOException e) {
            // It may have ended already; its input is closed either way.
        }
        try {
            CompletableFuture.anyOf(process.onExit(), cancel).get(nanos(timeout), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            kill(process);
            return new TimedOut();
        } catch (InterruptedException e) {
            kill(process);
            Thread.currentThread().interrupt();
            return new Interrupted();
        } catch (ExecutionException e) {
            // cancel completed exceptionally: a signal all the same.
        }
        if (!process.isAlive()) {
            return new Exited(process.exitValue());
        }
        kill(process);
        return new Cancelled();
    }

    /** {@code timeout} in nanoseconds, a timeout too long for them (some 292 years) as the longest. */
    private static long nanos(Duration timeout) {
        try {
            return timeout.toNanos();
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private static void kill(Process process) {
        ProcessTrees.killTree(process.toHandle());
    }
}
