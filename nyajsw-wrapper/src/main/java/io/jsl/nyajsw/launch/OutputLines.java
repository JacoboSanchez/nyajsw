package io.jsl.nyajsw.launch;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The recent output lines of one invocation, standard output and standard error together, each in
 * its order (two threads read the two streams, so how they interleave is approximate), for probes
 * that look for a line ({@code log}, plan §4.7). Lines are numbered from 0 as they come; a reader
 * keeps the number it has read to and asks for what came {@link #since} then.
 * Only the latest {@link #MAX_LINES} lines and {@link #MAX_CHARS} characters are kept, so a reader
 * that falls further behind misses the oldest ones and is told how many.
 *
 * <p>The lines are those the wrapper logs: masked, and long ones in pieces. Thread-safe.
 */
public final class OutputLines {

    /** The most lines kept. */
    public static final int MAX_LINES = 10_000;

    /** The most characters kept, all lines together. */
    public static final long MAX_CHARS = 2_000_000;

    /**
     * What came since a line number.
     *
     * @param lines   the lines, oldest first
     * @param next    the number to ask from next time
     * @param skipped lines that were no longer kept, before the first one returned
     */
    public record Page(List<String> lines, long next, long skipped) {

        public Page {
            lines = List.copyOf(lines);
        }
    }

    private final Deque<String> kept = new ArrayDeque<>();
    /** The number of the oldest line kept. */
    private long first;
    private long chars;

    /** Adds the next line. */
    public synchronized void add(String line) {
        kept.addLast(line);
        chars += line.length();
        while (kept.size() > MAX_LINES || (chars > MAX_CHARS && kept.size() > 1)) {
            chars -= kept.removeFirst().length();
            first++;
        }
    }

    /** The lines from number {@code from} on, as many as are still kept. */
    public synchronized Page since(long from) {
        long end = first + kept.size();
        long start = Math.max(from, first);
        if (start >= end) {
            return new Page(List.of(), Math.max(from, end), 0);
        }
        List<String> lines = new ArrayList<>((int) (end - start));
        long number = first;
        for (String line : kept) {
            if (number++ >= start) {
                lines.add(line);
            }
        }
        return new Page(lines, end, start - Math.max(from, 0));
    }
}
