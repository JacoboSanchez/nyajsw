package io.github.jacobosanchez.nyajsw.cli;

import io.github.jacobosanchez.nyajsw.config.WrapperConfig;
import io.github.jacobosanchez.nyajsw.launch.ClasspathExpander;
import io.github.jacobosanchez.nyajsw.launch.JavaCommandBuilder;
import io.github.jacobosanchez.nyajsw.lifecycle.Supervisor;
import io.github.jacobosanchez.nyajsw.log.WrapperLog;
import io.github.jacobosanchez.nyajsw.platform.ProcessTools;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Console mode ({@code -c}, plan P3.6): the application runs in the foreground under the {@link
 * Supervisor} ({@link Supervision}), and the wrapper returns the application's exit code.
 *
 * <p>{@link #stop()} is what Ctrl+C triggers, through the shutdown hook the entry point registers.
 * The application shares the console and receives the same Ctrl+C, so the default stop chain
 * ({@code terminate} alone) gives it the stop budget to finish its own shutdown before its process
 * tree is terminated.
 */
public final class ConsoleMode {

    private final WrapperConfig config;
    private final WrapperLog log;
    private final Supervision supervision;
    private final Object lock = new Object();
    /** Set once {@link #run()} has begun; guarded by {@link #lock}. */
    private boolean running;
    /** Completes with the exit code when {@link #run()} has finished, its last line logged. */
    private final CompletableFuture<Integer> finished = new CompletableFuture<>();

    /**
     * @param processes the platform's help with the application's processes ({@link ProcessTools#NONE} for none)
     * @throws IllegalArgumentException when a readiness probe type is not known
     */
    public ConsoleMode(WrapperConfig config, WrapperLog log, JavaCommandBuilder builder, ClasspathExpander expander,
            ProcessTools processes) {
        this.config = Objects.requireNonNull(config, "config");
        this.log = Objects.requireNonNull(log, "log");
        this.supervision = new Supervision(config, log, builder, expander, List.of(), (from, to) -> { }, processes);
    }

    /**
     * Logs the configuration's warnings and supervises the application until supervision is over.
     *
     * @return see {@link #exitCode}
     * @throws IllegalStateException when called a second time
     */
    public int run() {
        synchronized (lock) {
            if (running) {
                throw new IllegalStateException("console mode runs once");
            }
            running = true;
        }
        int code = ExitCode.UNEXPECTED_ERROR.code();
        try {
            Supervisor.Outcome outcome = supervision.run();
            code = Supervision.exitCode(outcome);
            log.status("supervision ended " + outcome.state() + "; exit code " + code + " is "
                    + (outcome.exitCode().isPresent() ? "the application's" : "the wrapper's"));
            return code;
        } finally {
            finished.complete(code);
        }
    }

    /**
     * Stops the application through the supervisor's stop chain and waits until {@link #run()} has
     * finished, at most {@link Supervisor#stopWait} of the stop budget: a supervisor stuck in a blocking call (a classpath on an unreachable share)
     * must not keep Ctrl+C from ending the wrapper. Safe to call at any time, also before {@link
     * #run()} (which then starts nothing), and more than once.
     *
     * @return the exit code {@link #run()} returns; 0 when it has not begun; {@link
     *         ExitCode#UNEXPECTED_ERROR} when it did not finish in time
     */
    public int stop() {
        supervision.requestStop();
        synchronized (lock) {
            if (!running) {
                return ExitCode.SUCCESS.code();
            }
        }
        Duration limit = Supervisor.stopWait(config.lifecycle().stopBudget());
        try {
            return finished.get(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.error("supervision did not end within " + limit.toSeconds() + " s of the stop request; ending the"
                    + " wrapper, the application may still run");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // run() completes the future normally, always
        }
        return ExitCode.UNEXPECTED_ERROR.code();
    }
}
