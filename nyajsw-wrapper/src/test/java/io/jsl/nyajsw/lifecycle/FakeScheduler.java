package io.jsl.nyajsw.lifecycle;

import java.time.Duration;
import java.util.Comparator;
import java.util.PriorityQueue;

/** A {@link Scheduler} on a fake clock: tasks run only when a test {@linkplain #advance advances} time. */
public final class FakeScheduler implements Scheduler {

    private record Task(Duration due, long order, Runnable action) {
    }

    private final PriorityQueue<Task> tasks = new PriorityQueue<>(
            Comparator.comparing(Task::due).thenComparingLong(Task::order));
    private Duration now = Duration.ZERO;
    private long scheduled;

    @Override
    public synchronized Duration now() {
        return now;
    }

    @Override
    public synchronized Timer schedule(Duration delay, Runnable task) {
        Task added = new Task(now.plus(delay), scheduled++, task);
        tasks.add(added);
        return () -> {
            synchronized (this) {
                tasks.remove(added);
            }
        };
    }

    /** Moves the clock forward by {@code by}, running every task that falls due, in order, on this thread. */
    public void advance(Duration by) {
        Duration until;
        synchronized (this) {
            until = now.plus(by);
        }
        while (true) {
            Task next;
            synchronized (this) {
                if (tasks.isEmpty() || tasks.peek().due().compareTo(until) > 0) {
                    now = until;
                    return;
                }
                next = tasks.poll();
                now = next.due();
            }
            next.action().run();
        }
    }

    /** Tasks that are neither run nor cancelled yet. */
    synchronized int pending() {
        return tasks.size();
    }
}
