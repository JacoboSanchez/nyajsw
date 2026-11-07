package io.github.jacobosanchez.nyajsw.lifecycle;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The real {@link Scheduler}: one daemon thread and {@link System#nanoTime()}. Its tasks only queue
 * an event, so one thread is enough; it is a daemon so that a forgotten scheduler never keeps the
 * wrapper alive, and cancelled tasks are dropped at once instead of waiting for their time.
 */
public final class ExecutorScheduler implements Scheduler, AutoCloseable {

    private final ScheduledThreadPoolExecutor executor;

    public ExecutorScheduler() {
        executor = new ScheduledThreadPoolExecutor(1,
                task -> Thread.ofPlatform().daemon().name("nyajsw-supervisor-timer").unstarted(task));
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    @Override
    public Duration now() {
        return Duration.ofNanos(System.nanoTime());
    }

    @Override
    public Timer schedule(Duration delay, Runnable task) {
        long nanos;
        try {
            nanos = delay.toNanos();
        } catch (ArithmeticException e) {
            nanos = Long.MAX_VALUE; // beyond 292 years: never, in practice
        }
        ScheduledFuture<?> future = executor.schedule(task, nanos, TimeUnit.NANOSECONDS);
        return () -> future.cancel(false);
    }

    /** Drops the pending tasks and ends the thread. */
    @Override
    public void close() {
        executor.shutdownNow();
    }
}
