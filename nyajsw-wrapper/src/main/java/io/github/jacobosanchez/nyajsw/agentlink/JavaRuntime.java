package io.github.jacobosanchez.nyajsw.agentlink;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * What the application's Java runtime can take: {@code -javaagent} needs the {@code java.instrument}
 * module, which a runtime image made with {@code jlink} may leave out, and then the JVM does not
 * start at all. The runtime's {@code release} file, beside its {@code bin} directory, lists its
 * modules ({@code MODULES="java.base ..."}) from Java 9 on.
 */
public final class JavaRuntime {

    private JavaRuntime() {
    }

    /**
     * Whether the runtime of {@code executable} ({@code <home>/bin/java}) certainly lacks {@code
     * java.instrument}: its {@code release} file lists its modules without it. A bare name (looked up
     * on {@code PATH}), a runtime without that file (Java 8) or one that cannot be read is given the
     * benefit of the doubt.
     */
    public static boolean lacksInstrumentation(String executable) {
        Path home;
        try {
            Path bin = Path.of(executable).toAbsolutePath().getParent();
            if (executable.indexOf('/') < 0 && executable.indexOf('\\') < 0 || bin == null || bin.getParent() == null) {
                return false;
            }
            home = bin.getParent();
        } catch (InvalidPathException e) {
            return false;
        }
        return modules(home.resolve("release")).map(modules -> !modules.contains("java.instrument")).orElse(false);
    }

    /** The modules a {@code release} file lists, if it lists any. */
    static Optional<List<String>> modules(Path release) {
        List<String> lines;
        try {
            lines = Files.readAllLines(release, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            return Optional.empty();
        }
        for (String line : lines) {
            if (line.startsWith("MODULES=")) {
                String value = line.substring("MODULES=".length()).strip();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                return Optional.of(Arrays.stream(value.split("\\s+")).filter(module -> !module.isEmpty()).toList());
            }
        }
        return Optional.empty();
    }
}
