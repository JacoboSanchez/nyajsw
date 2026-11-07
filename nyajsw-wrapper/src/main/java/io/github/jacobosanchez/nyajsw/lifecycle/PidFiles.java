package io.github.jacobosanchez.nyajsw.lifecycle;

import io.github.jacobosanchez.nyajsw.config.ConfigKeys;
import io.github.jacobosanchez.nyajsw.config.LifecycleConfig;
import io.github.jacobosanchez.nyajsw.config.SecretMasker;
import io.github.jacobosanchez.nyajsw.launch.ApplicationLauncher;
import io.github.jacobosanchez.nyajsw.launch.RunningApplication;
import io.github.jacobosanchez.nyajsw.log.WrapperLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The pid files of plan §4.6: {@code wrapper.pidfile} holds the wrapper's process id while it runs,
 * {@code wrapper.java.pidfile} the application's while an invocation runs. A file left by an
 * earlier run is overwritten; a file is removed only while it still holds the id this wrapper
 * wrote, so another instance's file is never deleted.
 *
 * <p>A pid file that cannot be written or removed is logged as an error and does not stop the
 * wrapper: the file is an aid for scripts, the application matters more. On Windows a reader that
 * holds the file open without delete sharing makes a write or removal fail; that is logged, not
 * retried. Paths are absolute ({@link LifecycleConfig}, D13), and are shown masked.
 */
public final class PidFiles {

    private final Optional<Path> wrapperFile;
    private final Optional<Path> applicationFile;
    private final SecretMasker secrets;
    private final WrapperLog log;

    /**
     * @param lifecycle where the files go, if anywhere
     * @param secrets   hides secrets in the paths shown in messages
     * @param log       the wrapper log
     */
    public PidFiles(LifecycleConfig lifecycle, SecretMasker secrets, WrapperLog log) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        this.wrapperFile = lifecycle.pidFile();
        this.applicationFile = lifecycle.javaPidFile();
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.log = Objects.requireNonNull(log, "log");
    }

    /** Writes the wrapper's process id to {@code wrapper.pidfile}, if set. */
    public void wrapperStarted(long pid) {
        wrapperFile.ifPresent(file -> write(file, pid, ConfigKeys.PIDFILE.name()));
    }

    /** Removes {@code wrapper.pidfile}, if set and still holding {@code pid}. */
    public void wrapperEnded(long pid) {
        wrapperFile.ifPresent(file -> remove(file, pid, ConfigKeys.PIDFILE.name()));
    }

    /**
     * A launcher that keeps {@code wrapper.java.pidfile} up to date around {@code launcher}: written
     * when an invocation has started, and removed when it has ended, before its exit is reported
     * through {@link RunningApplication#onExit()}, so the supervisor never starts the next
     * invocation, or ends, while the old file is still being removed. When the exit cannot be
     * observed the file stays, since the process may still run. Without the key it is {@code
     * launcher} itself.
     */
    public ApplicationLauncher tracking(ApplicationLauncher launcher) {
        Objects.requireNonNull(launcher, "launcher");
        if (applicationFile.isEmpty()) {
            return launcher;
        }
        Path file = applicationFile.get();
        return invocation -> {
            RunningApplication started = launcher.launch(invocation);
            write(file, started.pid(), ConfigKeys.JAVA_PIDFILE.name());
            return new Tracked(started, file);
        };
    }

    /** An invocation whose exit is reported only after its pid file is removed. */
    private final class Tracked implements RunningApplication {

        private final RunningApplication application;
        private final Path file;
        /** Built on first use, so that a failing {@code onExit()} fails where the supervisor asks for it. */
        private CompletableFuture<Integer> exit;

        Tracked(RunningApplication application, Path file) {
            this.application = application;
            this.file = file;
        }

        @Override
        public String name() {
            return application.name();
        }

        @Override
        public long pid() {
            return application.pid();
        }

        @Override
        public boolean isAlive() {
            return application.isAlive();
        }

        @Override
        public synchronized CompletableFuture<Integer> onExit() {
            if (exit == null) {
                long pid = application.pid();
                exit = application.onExit().whenComplete((code, error) -> {
                    if (error == null) {
                        remove(file, pid, ConfigKeys.JAVA_PIDFILE.name());
                    }
                });
            }
            return exit.copy();
        }

        @Override
        public CompletableFuture<Void> onProcessEnd() {
            return application.onProcessEnd();
        }

        @Override
        public void terminateTree() {
            application.terminateTree();
        }
    }

    private void write(Path file, long pid, String key) {
        // Written beside the file and moved over it, so that a reader never sees half a number. A
        // fixed name means a leftover from a crash is reused, not one more file per crash.
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Path directory = file.getParent();
            if (directory != null) {
                Files.createDirectories(directory);
            }
            try {
                Files.writeString(temporary, pid + System.lineSeparator(), StandardCharsets.US_ASCII,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                move(temporary, file);
            } finally {
                Files.deleteIfExists(temporary);
            }
            log.debug(() -> "wrote " + pid + " to " + shown(file) + " (" + key + ")");
        } catch (IOException | RuntimeException e) {
            log.error("cannot write the pid file " + shown(file) + " (" + key + "): " + reason(e));
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void remove(Path file, long pid, String key) {
        try {
            // Read as bytes to text without decoding errors: anything but our number is not ours.
            String content = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).strip();
            if (!content.equals(Long.toString(pid))) {
                log.status("the pid file " + shown(file) + " (" + key + ") no longer holds " + pid + "; left");
                return;
            }
            Files.delete(file);
            log.debug(() -> "removed " + shown(file) + " (" + key + ")");
        } catch (NoSuchFileException e) {
            // already gone
        } catch (IOException | RuntimeException e) {
            log.error("cannot remove the pid file " + shown(file) + " (" + key + "): " + reason(e));
        }
    }

    /** The exception's kind and, for a file system error, the operating system's reason, which names no path. */
    private String reason(Exception e) {
        String kind = e.getClass().getSimpleName();
        if (e instanceof FileSystemException failure && failure.getReason() != null) {
            return kind + ", " + secrets.maskText(failure.getReason());
        }
        return kind;
    }

    private String shown(Path file) {
        return secrets.maskText(file.toString());
    }
}
