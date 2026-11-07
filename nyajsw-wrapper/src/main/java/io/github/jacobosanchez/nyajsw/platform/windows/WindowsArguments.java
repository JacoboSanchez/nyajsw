package io.github.jacobosanchez.nyajsw.platform.windows;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Joins arguments into one Windows command line, quoted so that the program splits it back into the
 * same arguments: the rules of the C runtime and {@code CommandLineToArgvW}, which the Java launcher's
 * own parser follows for everything this class writes. Needed where Windows stores a command line as
 * a single string, such as a service's binary path. The first argument, the executable, is split by
 * {@code CreateProcess}'s simpler rule; that is safe because a path holds no double quote.
 */
final class WindowsArguments {

    private WindowsArguments() {
    }

    /** {@code arguments}, each quoted where needed, separated by single spaces. */
    static String join(List<String> arguments) {
        return arguments.stream().map(WindowsArguments::quote).collect(Collectors.joining(" "));
    }

    /**
     * {@code argument} as it must be written on a command line: unchanged when it holds no blank and
     * no double quote, else in double quotes, with each double quote escaped by a backslash and the
     * backslashes before a double quote (or before the closing one) doubled.
     */
    static String quote(String argument) {
        if (!argument.isEmpty() && argument.chars().noneMatch(c -> c == ' ' || c == '\t' || c == '\n'
                || c == 0x0B || c == '"')) {
            return argument;
        }
        StringBuilder quoted = new StringBuilder("\"");
        int backslashes = 0;
        for (char c : argument.toCharArray()) {
            if (c == '\\') {
                backslashes++;
                continue;
            }
            quoted.append("\\".repeat(c == '"' ? 2 * backslashes + 1 : backslashes)).append(c);
            backslashes = 0;
        }
        return quoted.append("\\".repeat(2 * backslashes)).append('"').toString();
    }
}
