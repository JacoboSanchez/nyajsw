package io.jsl.nyajsw.lifecycle;

import io.jsl.nyajsw.config.ConfigException;
import io.jsl.nyajsw.config.ConfigKeys;
import io.jsl.nyajsw.config.LifecycleConfig;
import io.jsl.nyajsw.launch.ExternalCommand;
import io.jsl.nyajsw.log.WrapperLog;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code command} stop strategy (plan §4.6, P7.5): it runs {@code wrapper.stop.command}, such
 * as a {@code .bat} file that calls the application's own shutdown, as an {@link ExternalCommand}
 * in the application's working directory, then leaves the waiting to the supervisor. The command
 * runs on a thread of its own, since the supervisor's thread must not block on it, and is bounded
 * by the strategy's share: one still running when the share runs out is killed with what it
 * started, and so is one still running when the application has ended or supervision ends ({@link
 * #close()}, which waits for that kill and its log line), so that it never outlives the wrapper.
 * It asks the application to end; it is not a hook that runs after the stop. Its exit code is
 * logged, nothing else follows from it: the supervisor waits out the share either way.
 *
 * <p>Log messages name the program only, not its arguments, which can carry credentials. Thread
 * safe: the runs in progress are the only state.
 */
public final class CommandStopStrategy implements StopStrategy {

    /** The name the configuration uses. */
    public static final String NAME = "command";

    /** How long {@link #close()} waits for a run to be killed and logged; a kill takes moments. */
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(10);

    private final ExternalCommand command;
    /** The runs in progress: the signal that ends each one, and the thread that runs it. */
    private final Map<CompletableFuture<Void>, Thread> running = new ConcurrentHashMap<>();

    CommandStopStrategy(ExternalCommand command) {
        this.command = Objects.requireNonNull(command, "command");
    }

    /**
     * The strategy {@code config} describes.
     *
     * @throws ConfigException when {@code wrapper.stop.command} is not set
     */
    static CommandStopStrategy from(LifecycleConfig config) {
        if (config.stopCommand().isEmpty()) {
            throw new ConfigException(ConfigKeys.STOP_COMMAND.name() + " is not set");
        }
        return new CommandStopStrategy(new ExternalCommand(config.stopCommand(), config.workingDir()));
    }

    @Override
    public Result requestStop(Context context) {
        CompletableFuture<Void> ended = context.application().onProcessEnd();
        if (ended.isDone()) {
            return Result.NOT_APPLICABLE; // nothing left to ask
        }
        CompletableFuture<Void> closed = new CompletableFuture<>();
        CompletableFuture<Object> cancel = CompletableFuture.anyOf(ended, closed);
        Thread thread = Thread.ofVirtual().name("nyajsw-stop-command").unstarted(() -> {
            try {
                report(context, command.run(context.share(), cancel), ended.isDone());
            } catch (RuntimeException e) {
                context.log().error("the stop command " + command.program() + " failed", e);
            } finally {
                running.remove(closed);
            }
        });
        // Registered before it starts, so that a close right after this cannot miss it.
        running.put(closed, thread);
        thread.start();
        return Result.REQUESTED;
    }

    /**
     * Kills the commands still running and waits, at most {@link #CLOSE_WAIT} each, until they are
     * logged. An interrupt does not cut the wait short, since supervision that was stopped by one
     * closes with the interrupt status set and the wrapper may exit right after: the status is
     * cleared for the wait and set again afterwards.
     */
    @Override
    public void close() {
        List<Map.Entry<CompletableFuture<Void>, Thread>> runs = List.copyOf(running.entrySet());
        runs.forEach(run -> run.getKey().complete(null));
        boolean interrupted = Thread.interrupted();
        try {
            for (Map.Entry<CompletableFuture<Void>, Thread> run : runs) {
                long deadline = System.nanoTime() + CLOSE_WAIT.toNanos();
                while (run.getValue().isAlive()) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        break;
                    }
                    try {
                        run.getValue().join(Duration.ofNanos(left));
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void report(Context context, ExternalCommand.Outcome outcome, boolean applicationEnded) {
        WrapperLog log = context.log();
        String name = "the stop command " + command.program();
        switch (outcome) {
            case ExternalCommand.Exited(int code) when code == 0 -> log.status(name + " exited with 0");
            case ExternalCommand.TimedOut() -> log.status(ExternalCommand.describe(outcome, name, context.share()));
            case ExternalCommand.Cancelled() -> log.status(name + " was ended, since "
                    + (applicationEnded ? context.application().name() + " has ended" : "supervision has ended"));
            default -> log.error(ExternalCommand.describe(outcome, name, context.share()));
        }
    }
}
