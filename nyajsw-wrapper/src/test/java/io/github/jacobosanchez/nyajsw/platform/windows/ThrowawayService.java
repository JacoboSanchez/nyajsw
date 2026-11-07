package io.github.jacobosanchez.nyajsw.platform.windows;

import static org.testng.Assert.assertTrue;

import io.github.jacobosanchez.nyajsw.TestConfigs;
import io.github.jacobosanchez.nyajsw.log.WrapperLog;
import io.github.jacobosanchez.nyajsw.platform.HostOs;
import io.github.jacobosanchez.nyajsw.platform.ServiceException;
import io.github.jacobosanchez.nyajsw.platform.ServiceState;
import io.github.jacobosanchez.nyajsw.platform.ServiceStatus;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.testng.Reporter;
import org.testng.SkipException;

/**
 * The fixture of the tests against the real Service Control Manager (AGENTS.md §4.5): a temporary
 * directory, a service name {@code nyajsw-test-<random>}, and a controller whose binary path runs
 * this build on the test JVM's full classpath; {@link #close} stops and removes the service, also
 * one that is still pending, and deletes the directory, also when a test failed.
 *
 * <p>A test JVM killed in between can leave a service behind, with its wrapper running as {@code
 * LocalSystem}: {@code sc query state= all | findstr nyajsw-test-} finds it, {@code sc stop <name>}
 * and {@code sc delete <name>} remove it.
 */
final class ThrowawayService implements AutoCloseable {

    /** How long one step against the Service Control Manager may take. */
    static final Duration WAIT = Duration.ofSeconds(60);

    final Path dir;
    final String name;
    final ServiceCommandLine commandLine;
    final WindowsServiceController controller;

    private ThrowawayService(Path dir, String name, ServiceCommandLine commandLine) {
        this.dir = dir;
        this.name = name;
        this.commandLine = commandLine;
        this.controller = new WindowsServiceController(new JnaScmApi(), new JnaLsaApi(), commandLine, StateWait.REAL);
    }

    /**
     * A fresh fixture, with a {@code tmp} directory in it for the service's own temporary files.
     *
     * @throws SkipException on a host that is not Windows
     */
    static ThrowawayService create() throws IOException {
        if (!HostOs.isWindows()) {
            throw new SkipException("the Service Control Manager exists on Windows only");
        }
        Path dir = Files.createTempDirectory("nyajsw-scm").toRealPath();
        Files.createDirectories(dir.resolve("tmp"));
        return new ThrowawayService(dir, "nyajsw-test-" + UUID.randomUUID().toString().substring(0, 8),
                new ServiceCommandLine(Path.of(System.getProperty("java.home")), serviceClasspath(),
                        ServiceCommandLine.nativeCharset()));
    }

    /** The test JVM's full classpath (surefire runs it from a manifest-only jar): the wrapper, JNA, the test application. */
    private static List<Path> serviceClasspath() {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        List<Path> entries = Arrays.stream(classpath.split(File.pathSeparator)).filter(entry -> !entry.isBlank())
                .map(entry -> Path.of(entry).toAbsolutePath()).toList();
        assertTrue(entries.stream().anyMatch(entry -> entry.getFileName().toString().startsWith("jna-platform"))
                && entries.contains(locationOf(WindowsServiceController.class)), "the service needs the wrapper's"
                + " classes and JNA: " + entries);
        return entries;
    }

    /** Where {@code type} was loaded from: a classes directory or a jar. */
    static Path locationOf(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code file} as UTF-8, the wrapper log's default encoding on Java 18 and later; bad bytes replaced. */
    static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /**
     * Removes the service and the directory: stopped first, a pending one waited for and killed as a
     * last resort, so that nothing holds the directory; with {@code report}, the logs go into the
     * test report before the directory is deleted.
     */
    void close(boolean report) throws InterruptedException {
        try {
            stopQuietly();
            try {
                controller.remove(name);
            } catch (ServiceException e) {
                int code = e.errorCode();
                if (code != Win32Errors.SERVICE_DOES_NOT_EXIST && code != Win32Errors.SERVICE_MARKED_FOR_DELETE) {
                    throw e;
                }
            }
        } finally {
            if (report) {
                report();
            }
            deleteDirectory();
        }
    }

    @Override
    public void close() throws InterruptedException {
        close(false);
    }

    private void stopQuietly() throws InterruptedException {
        try {
            long deadline = System.nanoTime() + WAIT.toNanos();
            Optional<ServiceStatus> status = controller.status(name);
            while (status.isPresent() && status.get().state() != ServiceState.STOPPED) {
                if (status.get().state() == ServiceState.RUNNING) {
                    controller.stop(name, WAIT);
                    return;
                }
                if (System.nanoTime() > deadline) { // pending, and it takes no stop request
                    status.get().pid().stream().mapToObj(ProcessHandle::of).flatMap(Optional::stream)
                            .forEach(process -> {
                                Reporter.log("killing the process " + process.pid() + " of " + name
                                        + ", which did not stop", true);
                                process.destroyForcibly();
                            });
                    return;
                }
                Thread.sleep(200); // the real SCM, no event to wait on
                status = controller.status(name);
            }
        } catch (ServiceException e) {
            Reporter.log("could not stop " + name + ": " + e.getMessage(), true);
        }
    }

    /**
     * Deletes the directory once the wrapper's process lets go of it: the JNA library it unpacked
     * there stays in use until that process has ended, a moment after {@code STOPPED}. One that is
     * still held is left behind with a warning, not a failure.
     */
    private void deleteDirectory() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (true) {
            try {
                TestConfigs.delete(dir);
                return;
            } catch (IOException e) {
                if (System.nanoTime() > deadline) {
                    Reporter.log("left " + dir + " behind: " + e, true);
                    return;
                }
                Thread.sleep(200); // no event says when the process has let go of its files
            }
        }
    }

    /** The wrapper log and the bootstrap logs, into the test report and its output. */
    private void report() {
        List<Path> logs = List.of(dir.resolve("wrapper.log"), WrapperLog.bootstrapFile(dir, name),
                WrapperLog.bootstrapFile(dir.resolve("tmp"), name));
        for (Path log : logs) {
            if (Files.exists(log)) {
                Reporter.log("----- " + log + " -----", true);
                try {
                    Reporter.log(read(log), true);
                } catch (IOException e) {
                    Reporter.log("cannot read it: " + e, true);
                }
            }
        }
    }
}
