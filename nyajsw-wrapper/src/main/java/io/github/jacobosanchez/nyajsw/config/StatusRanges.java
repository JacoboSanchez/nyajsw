package io.github.jacobosanchez.nyajsw.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The HTTP status codes the {@code http} probe accepts ({@code wrapper.readiness.http.status}):
 * codes and ranges separated by commas, such as {@code 200-399} or {@code 200,204,300-302}.
 *
 * @param ranges the accepted ranges, at least one; a single code is a range of one
 */
public record StatusRanges(List<Range> ranges) {

    private static final Pattern ITEM = Pattern.compile("([0-9]{3})(?:\\s*-\\s*([0-9]{3}))?");

    /**
     * One range of codes, both ends included.
     *
     * @param from the lowest code, from 100
     * @param to   the highest code, at most 599
     */
    public record Range(int from, int to) {

        public Range {
            if (from < 100 || to > 599 || from > to) {
                throw new IllegalArgumentException("not a range of HTTP status codes: " + from + "-" + to);
            }
        }

        @Override
        public String toString() {
            return from == to ? Integer.toString(from) : from + "-" + to;
        }
    }

    public StatusRanges {
        ranges = List.copyOf(ranges);
        if (ranges.isEmpty()) {
            throw new IllegalArgumentException("no status code accepted");
        }
    }

    /**
     * Reads {@code text}; blanks around the items and their dash are ignored.
     *
     * @throws IllegalArgumentException with a reason that does not quote {@code text}
     */
    public static StatusRanges parse(String text) {
        Objects.requireNonNull(text, "text");
        List<Range> ranges = new ArrayList<>();
        for (String item : text.split(",", -1)) {
            Matcher matcher = ITEM.matcher(item.strip());
            if (!matcher.matches()) {
                throw new IllegalArgumentException("expected HTTP status codes or ranges such as 200-399 or 200,204");
            }
            int from = Integer.parseInt(matcher.group(1));
            int to = matcher.group(2) == null ? from : Integer.parseInt(matcher.group(2));
            try {
                ranges.add(new Range(from, to));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("HTTP status codes go from 100 to 599, the lower end first");
            }
        }
        return new StatusRanges(ranges);
    }

    /** Whether {@code status} is accepted. */
    public boolean contains(int status) {
        return ranges.stream().anyMatch(range -> status >= range.from() && status <= range.to());
    }

    /** The ranges as they can be written, such as {@code 200-399}. */
    @Override
    public String toString() {
        return String.join(",", ranges.stream().map(Range::toString).toList());
    }
}
