package io.github.jacobosanchez.nyajsw.agentlink;

import io.github.jacobosanchez.nyajsw.agent.Agent;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Finds the agent's jar, which {@code -javaagent} needs as a file: the jar the agent classes were
 * loaded from, when its manifest names the agent as {@code Premain-Class} (the {@code nyajsw-agent}
 * jar on the wrapper's classpath); otherwise {@value #FILE_NAME} beside it (a wrapper jar that
 * bundles the agent classes, P8.1). Classes loaded from a directory, as in a build, have no jar,
 * and neither does a path with {@code =} in it, which {@code -javaagent} would cut there.
 */
public final class AgentJar {

    /** The name of the agent's jar in a distribution. */
    public static final String FILE_NAME = "nyajsw-agent.jar";

    private AgentJar() {
    }

    /** The agent's jar for this wrapper, if there is one. */
    public static Optional<Path> locate() {
        CodeSource source = Agent.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            return Optional.empty();
        }
        Path location;
        try {
            location = Path.of(source.getLocation().toURI());
        } catch (URISyntaxException | IllegalArgumentException | FileSystemNotFoundException e) {
            return Optional.empty();
        }
        return locate(location);
    }

    /** The agent's jar for agent classes loaded from {@code location}, a jar or a directory. */
    static Optional<Path> locate(Path location) {
        return find(location).filter(jar -> jar.toString().indexOf('=') < 0);
    }

    private static Optional<Path> find(Path location) {
        if (Files.isRegularFile(location) && isAgentJar(location)) {
            return Optional.of(location);
        }
        Path parent = location.toAbsolutePath().getParent();
        if (Files.isRegularFile(location) && parent != null) {
            Path beside = parent.resolve(FILE_NAME);
            if (Files.isRegularFile(beside) && isAgentJar(beside)) {
                return Optional.of(beside);
            }
        }
        return Optional.empty();
    }

    /** Whether {@code jar} names the agent as its {@code Premain-Class}. */
    static boolean isAgentJar(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            Manifest manifest = file.getManifest();
            return manifest != null && Agent.class.getName().equals(manifest.getMainAttributes().getValue(
                    "Premain-Class"));
        } catch (IOException e) {
            return false;
        }
    }
}
