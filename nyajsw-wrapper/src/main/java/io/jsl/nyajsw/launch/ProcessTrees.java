package io.jsl.nyajsw.launch;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Kills what a process started. On Windows killing a process leaves its children running, so the
 * caller kills the process itself first, so that it cannot start more, and then hands its
 * descendants, read before that kill, to {@link #killDescendants}: it kills each one and looks again
 * below it until nothing new turns up. A process started in the moment between finding and killing
 * its parent can still escape; a job object closes that gap where there is one (P6.4).
 */
public final class ProcessTrees {

    private ProcessTrees() {
    }

    /**
     * How a kill went.
     *
     * @param descendants the descendant processes found
     * @param failed      how many of them could not be killed
     */
    public record Killed(int descendants, int failed) {
    }

    /** Kills {@code process} and then its descendants, read first so that they are still known. */
    public static Killed killTree(ProcessHandle process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        process.destroyForcibly();
        return killDescendants(descendants);
    }

    /** Kills {@code descendants} and, looking again below each one, whatever they started. */
    public static Killed killDescendants(Collection<ProcessHandle> descendants) {
        Deque<ProcessHandle> pending = new ArrayDeque<>(descendants);
        Set<Long> seen = new HashSet<>();
        int found = 0;
        int failed = 0;
        while (!pending.isEmpty()) {
            ProcessHandle next = pending.poll();
            if (!seen.add(next.pid())) {
                continue;
            }
            List<ProcessHandle> below = next.descendants().toList();
            found++;
            if (next.isAlive() && !next.destroyForcibly()) {
                failed++;
            }
            pending.addAll(below);
        }
        return new Killed(found, failed);
    }
}
