package io.github.jacobosanchez.nyajsw.cli;

import io.github.jacobosanchez.nyajsw.config.ConfigWarning;
import io.github.jacobosanchez.nyajsw.config.WrapperConfig;
import io.github.jacobosanchez.nyajsw.launch.ApplicationProcess;
import io.github.jacobosanchez.nyajsw.launch.ClasspathExpander;
import io.github.jacobosanchez.nyajsw.launch.JavaCommand;
import io.github.jacobosanchez.nyajsw.launch.JavaCommandBuilder;
import io.github.jacobosanchez.nyajsw.log.WrapperLog;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Console mode ({@code -c}, plan P2.5): runs the application once in the foreground and returns its
 * exit code. There is no restart loop yet; the supervisor takes over in P3.6.
 *
 * <p>{@link #stop()} is what Ctrl+C triggers, through the shutdown hook the entry point registers.
 * The application shares the console and receives the same Ctrl+C, so it is given {@code
 * wrapper.shutdown.timeout} to finish its own shutdown before its process tree is terminated.
 */
public final class ConsoleMode {

    /** How long to wait for a terminated process to be gone. */
    private static final Duration AFTER_TERMINATION = Duration.ofSeconds(10);

    private final WrapperConfig config;
    private final WrapperLog log;
    private final JavaCommandBuilder builder;
    private final ClasspathExpander expander;
    private final Object lock = new Object();
    /** Set once the application has started; guarded by {@link #lock}. */
    private ApplicationProcess process;
    /** Set once {@link #stop()} has been called; guarded by {@link #lock}. */
    private boolean stopping;
    /** Completes when {@link #stop()} has finished, so that {@link #run()} does not return under it. */
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();

    public ConsoleMode(WrapperConfig config, WrapperLog log, JavaCommandBuilder builder, ClasspathExpander expander) {
        this.config = Objects.requireNonNull(config, "config");
        this.log = Objects.requireNonNull(log, "log");
        this.builder = Objects.requireNonNull(builder, "builder");
        this.expander = Objects.requireNonNull(expander, "expander");
    }

    /**
     * Logs the configuration's warnings, starts the application and waits for it.
     *
     * @return the application's exit code; 0 when {@link #stop()} came before the start
     * @throws io.github.jacobosanchez.nyajsw.launch.LaunchException when the application cannot be started
     */
    public int run() throws InterruptedException {
        for (ConfigWarning warning : config.warnings()) {
            log.status("warning: " + warning);
        }
        ClasspathExpander.Expanded classpath = expander.expand(config.java().classpath(), config.java().workingDir(),
                config.secrets());
        warn(classpath.warnings());
        JavaCommand command = builder.build(config.java(), classpath.entries(), config.secrets());
        warn(command.warnings());
        ApplicationProcess started;
        synchronized (lock) {
            if (stopping) {
                log.status("stopped before the application was started");
                return 0;
            }
            started = ApplicationProcess.start(command, config.java(), 1, config.secrets(), log);
            process = started;
        }
        int code = started.waitFor();
        boolean stopRequested;
        synchronized (lock) {
            stopRequested = stopping;
        }
        if (stopRequested) {
            // The application ended because of stop(), which may still be logging; the caller closes the log.
            stopped.join();
        }
        return code;
    }

    /**
     * Stops the application: waits up to {@code wrapper.shutdown.timeout} for it to end by itself
     * (it received the same Ctrl+C), then terminates its process tree. Safe to call at any time,
     * also before {@link #run()} has started anything, and more than once.
     */
    public void stop() {
        ApplicationProcess running;
        synchronized (lock) {
            stopping = true;
            running = process;
        }
        if (running == null) {
            stopped.complete(null);
            return;
        }
        Duration timeout = config.lifecycle().shutdownTimeout();
        try {
            log.status("stopping: waiting up to " + timeout.toSeconds() + " s for " + running.name() + " to end");
            Optional<Integer> code = running.waitFor(timeout);
            if (code.isEmpty()) {
                log.status(running.name() + " did not end within " + timeout.toSeconds() + " s; terminating it");
                running.terminateTree();
                running.waitFor(AFTER_TERMINATION);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running.terminateTree();
        } finally {
            stopped.complete(null);
        }
    }

    private void warn(List<String> warnings) {
        for (String warning : warnings) {
            log.status("warning: " + warning);
        }
    }
}
