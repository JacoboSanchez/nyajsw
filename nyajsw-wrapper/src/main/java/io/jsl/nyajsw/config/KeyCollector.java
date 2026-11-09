package io.jsl.nyajsw.config;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Collects the members of a key family, such as every {@code wrapper.java.additional.<n>} or
 * {@code wrapper.app.env.<variable>} entry, in the order the typed configuration needs (plan §4.5):
 *
 * <ul>
 *   <li>Indices ({@code <n>}) sort by {@link IndexOrder}, so {@code wrapper.java.classpath.10} comes
 *       after {@code .9} and named indices such as {@code .lib} after the numbers; exit codes
 *       ({@code <code>}) sort numerically; gaps are allowed.</li>
 *   <li>Members that differ only in free placeholders keep the order of the input, which is file
 *       order. In a template mixing free and ordered placeholders, ordering looks at the ordered ones
 *       only, so members sharing a free value are not grouped together.</li>
 *   <li>Two keys with the same index or exit code written differently ({@code .7} and {@code .07})
 *       are both returned, in input order, with a warning, since only one was probably meant; the
 *       consumer decides what that means for its family (a list keeps both, a map of exit codes
 *       cannot). {@link Member#numbers()} gives the exit codes to key on.</li>
 * </ul>
 *
 * <p>A key belongs to a family only when the registry resolves it to that declaration: with
 * {@code wrapper.on_exit.0} declared on its own, it is not a member of {@code wrapper.on_exit.<code>}.
 */
public final class KeyCollector {

    private final KeyRegistry registry;

    public KeyCollector(KeyRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * @param spec    the family (or single key) to collect; must be declared in the registry
     * @param entries the effective entries, at most one per key
     * @return the members in order, and warnings about indexes written twice
     * @throws IllegalArgumentException if {@code spec} is not in the registry or a key occurs twice
     */
    public Collected collect(KeySpec spec, List<ConfigEntry> entries) {
        Objects.requireNonNull(spec, "spec");
        if (registry.lookup(spec.pattern().example()).orElse(null) != spec) {
            throw new IllegalArgumentException("not declared in this registry: " + spec.name());
        }
        List<Member> members = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (ConfigEntry entry : entries) {
            if (!keys.add(entry.key())) {
                throw new IllegalArgumentException("key occurs twice, merge layers first: " + entry.key());
            }
            if (registry.lookup(entry.key()).orElse(null) == spec) {
                List<String> placeholders = spec.pattern().captures(entry.key()).orElseThrow();
                members.add(new Member(entry, placeholders, numbers(spec.pattern(), placeholders)));
            }
        }
        KeyPattern pattern = spec.pattern();
        members.sort(order(pattern)); // stable: ties keep input order
        return new Collected(members, duplicateIndexWarnings(pattern, members));
    }

    /** The exit-code placeholder values; the pattern only matched decimal integers there. */
    private static List<BigInteger> numbers(KeyPattern pattern, List<String> placeholders) {
        List<BigInteger> numbers = new ArrayList<>();
        for (int position = 0; position < placeholders.size(); position++) {
            if (pattern.isNumeric(position)) {
                numbers.add(new BigInteger(placeholders.get(position)));
            }
        }
        return numbers;
    }

    private static Comparator<Member> order(KeyPattern pattern) {
        return (first, second) -> {
            int number = 0;
            for (int position = 0; position < pattern.labels().size(); position++) {
                int order = 0;
                if (pattern.isIndex(position)) {
                    order = IndexOrder.NATURAL.compare(first.placeholders().get(position),
                            second.placeholders().get(position));
                } else if (pattern.isNumeric(position)) {
                    order = first.numbers().get(number).compareTo(second.numbers().get(number));
                    number++;
                }
                if (order != 0) {
                    return order;
                }
            }
            return 0;
        };
    }

    private static List<ConfigWarning> duplicateIndexWarnings(KeyPattern pattern, List<Member> members) {
        if (pattern.orderedPlaceholders() == 0) {
            return List.of();
        }
        List<ConfigWarning> warnings = new ArrayList<>();
        Map<List<Object>, Member> seen = new HashMap<>();
        for (Member member : members) {
            Member earlier = seen.putIfAbsent(identity(pattern, member), member);
            if (earlier != null) {
                warnings.add(new ConfigWarning(member.entry().location(), member.entry().key()
                        + " has the same index as " + earlier.entry().key() + " (" + earlier.entry().location()
                        + "); only one was probably meant"));
            }
        }
        return warnings;
    }

    /** The placeholder values with ordered ones normalised, so {@code 7} and {@code 07} are equal. */
    private static List<Object> identity(KeyPattern pattern, Member member) {
        List<Object> identity = new ArrayList<>();
        int number = 0;
        for (int position = 0; position < pattern.labels().size(); position++) {
            String value = member.placeholders().get(position);
            if (pattern.isIndex(position)) {
                identity.add(IndexOrder.normalise(value));
            } else if (pattern.isNumeric(position)) {
                identity.add(member.numbers().get(number++));
            } else {
                identity.add(value);
            }
        }
        return identity;
    }

    /**
     * One member of a family.
     *
     * @param entry        the entry
     * @param placeholders what the placeholders stand for, in template order (see
     *                     {@link KeyPattern#captures(String)})
     * @param numbers      the values of the exit-code placeholders ({@code <code>}) in template
     *                     order, normalised ({@code 07} is 7); arbitrary size, so a huge code never
     *                     overflows
     */
    public record Member(ConfigEntry entry, List<String> placeholders, List<BigInteger> numbers) {

        public Member {
            Objects.requireNonNull(entry, "entry");
            placeholders = List.copyOf(placeholders);
            numbers = List.copyOf(numbers);
        }

        /** The key, e.g. {@code wrapper.app.env.PATH}. */
        public String key() {
            return entry.key();
        }

        /** The value. */
        public String value() {
            return entry.value();
        }

        /** The value of the last placeholder, e.g. {@code PATH} or {@code 3}. */
        public String lastPlaceholder() {
            if (placeholders.isEmpty()) {
                throw new IllegalStateException("exact key has no placeholders: " + entry.key());
            }
            return placeholders.get(placeholders.size() - 1);
        }
    }

    /**
     * The outcome of {@link #collect}.
     *
     * @param members  the members in order, unmodifiable
     * @param warnings warnings about indexes written twice, unmodifiable
     */
    public record Collected(List<Member> members, List<ConfigWarning> warnings) {

        public Collected {
            members = List.copyOf(members);
            warnings = List.copyOf(warnings);
        }

        /** The values in order, e.g. the JVM options of {@code wrapper.java.additional.<n>}. */
        public List<String> values() {
            return members.stream().map(Member::value).toList();
        }
    }
}
