package io.github.jacobosanchez.nyajsw;

import io.github.jacobosanchez.nyajsw.config.ConfigKeys;
import io.github.jacobosanchez.nyajsw.config.ConfigLoader;
import io.github.jacobosanchez.nyajsw.config.WrapperConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Typed configurations for tests, loaded from a {@code wrapper.conf} written for the purpose. */
public final class TestConfigs {

    private TestConfigs() {
    }

    /**
     * Writes {@code dir/wrapper.conf} with a main class and {@code lines}, and loads it without
     * system properties or environment, so relative paths resolve against {@code dir}.
     */
    public static WrapperConfig load(Path dir, String... lines) throws IOException {
        List<String> all = new ArrayList<>(List.of("wrapper.java.app.mainclass=Main"));
        all.addAll(List.of(lines));
        Path conf = Files.write(dir.resolve("wrapper.conf"), all);
        return WrapperConfig.from(new ConfigLoader(Map.of(), Map.of(), false, ConfigKeys.registry()).load(conf));
    }

    /** Deletes {@code dir} and everything in it. */
    public static void delete(Path dir) throws IOException {
        try (var paths = Files.walk(dir)) {
            for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
