package io.github.jacobosanchez.nyajsw.launch;

import io.github.jacobosanchez.nyajsw.config.SecretMasker;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The command line of the application, as the argument array {@code ProcessBuilder} receives, never
 * as one string passed through a shell (AGENTS.md §4.7).
 *
 * <p>{@link #loggable()}, which {@link #toString()} returns, is the only form to show: it hides the
 * configuration's secrets wherever they occur in an argument, because a password copied into a JVM
 * option with <code>${...}</code> would otherwise reach the log.
 */
public final class JavaCommand {

    private final List<String> arguments;
    private final List<String> warnings;
    private final SecretMasker secrets;

    /**
     * @param arguments the executable followed by its arguments
     * @param warnings  settings that were ignored while building it, for the caller to log
     * @param secrets   hides the configuration's secrets in {@link #loggable()}
     */
    public JavaCommand(List<String> arguments, List<String> warnings, SecretMasker secrets) {
        this.arguments = List.copyOf(arguments);
        if (this.arguments.isEmpty()) {
            throw new IllegalArgumentException("a command needs at least the executable");
        }
        this.warnings = List.copyOf(warnings);
        this.secrets = Objects.requireNonNull(secrets, "secrets");
    }

    /** The executable followed by its arguments, unmodifiable; never log these, see {@link #loggable()}. */
    public List<String> arguments() {
        return arguments;
    }

    /** Settings that were ignored while building the command, for the caller to log. */
    public List<String> warnings() {
        return warnings;
    }

    /** The executable. */
    public String executable() {
        return arguments.get(0);
    }

    /**
     * The command line for the log: arguments masked, and quoted when they contain blanks so that
     * the line reads the way it would be typed. Not a command to run.
     */
    public String loggable() {
        return arguments.stream()
                .map(secrets::maskText)
                .map(argument -> argument.isEmpty() || argument.chars().anyMatch(Character::isWhitespace)
                        ? '"' + argument + '"'
                        : argument)
                .collect(Collectors.joining(" "));
    }

    /** The {@link #loggable()} form, so that a command printed by accident shows no secret. */
    @Override
    public String toString() {
        return loggable();
    }
}
