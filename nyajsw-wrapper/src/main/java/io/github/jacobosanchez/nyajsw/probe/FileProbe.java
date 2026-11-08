package io.github.jacobosanchez.nyajsw.probe;

import io.github.jacobosanchez.nyajsw.config.ConfigKeys;
import io.github.jacobosanchez.nyajsw.config.ProbeConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The {@code file} probe: it passes while {@code wrapper.readiness.file.path} exists, as a file or a
 * directory. A file left over from an earlier run counts as well, so an application that signals
 * readiness with a file should delete it when it starts or stops.
 */
public final class FileProbe implements Probe {

    /** The type name the configuration uses. */
    public static final String TYPE = "file";

    private final Path path;

    FileProbe(Path path) {
        this.path = Objects.requireNonNull(path, "path");
    }

    /**
     * The probe {@code config} describes.
     *
     * @throws IllegalArgumentException when {@code wrapper.readiness.file.path} is not set
     */
    static FileProbe from(ProbeConfig config) {
        return new FileProbe(config.filePath().orElseThrow(() -> new IllegalArgumentException("the " + TYPE
                + " probe needs " + ConfigKeys.READINESS_FILE_PATH.name())));
    }

    @Override
    public ProbeResult check(ProbeContext context) {
        return Files.exists(path) ? ProbeResult.pass(path + " exists") : ProbeResult.fail(path + " does not exist");
    }
}
