package io.github.jacobosanchez.nyajsw.config;

import io.github.jacobosanchez.nyajsw.NyajswException;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The configuration cannot be loaded or is invalid. The message starts with the location when one
 * is known, e.g. {@code C:\app\conf\wrapper.conf:12: included file not found: ...}.
 *
 * <p>Validation reports every problem at once (plan §4.9): {@link #aggregate(List)} combines several
 * exceptions into one whose message lists them all and whose {@link #problems()} returns them.
 */
public class ConfigException extends NyajswException {

    private static final long serialVersionUID = 1L;

    private final transient ConfigLocation location;
    private final transient List<ConfigException> problems;

    public ConfigException(String message) {
        this(null, message, null);
    }

    public ConfigException(ConfigLocation location, String message) {
        this(location, message, null);
    }

    public ConfigException(ConfigLocation location, String message, Throwable cause) {
        super(location == null ? message : location + ": " + message, cause);
        this.location = location;
        this.problems = List.of(this);
    }

    private ConfigException(List<ConfigException> problems) {
        super(problems.size() + " configuration errors:" + problems.stream()
                .map(problem -> System.lineSeparator() + "  " + problem.getMessage())
                .collect(Collectors.joining()));
        this.location = null;
        this.problems = List.copyOf(problems);
    }

    /**
     * One exception for several problems: the only one when there is one, otherwise an exception
     * listing them all.
     *
     * @throws IllegalArgumentException if {@code problems} is empty
     */
    public static ConfigException aggregate(List<ConfigException> problems) {
        if (problems.isEmpty()) {
            throw new IllegalArgumentException("no problems to report");
        }
        List<ConfigException> flat = problems.stream().flatMap(problem -> problem.problems().stream()).toList();
        return flat.size() == 1 ? flat.get(0) : new ConfigException(flat);
    }

    /** The individual problems: this exception alone, or those it aggregates. */
    public List<ConfigException> problems() {
        return problems != null ? problems : List.of(this); // null after deserialisation (transient)
    }

    /** The file and line the problem was found at, when known. */
    public Optional<ConfigLocation> location() {
        return Optional.ofNullable(location);
    }
}
