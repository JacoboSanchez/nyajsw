package io.jsl.nyajsw.platform.windows;

import io.jsl.nyajsw.platform.PlatformException;
import io.jsl.nyajsw.platform.ProcessJob;
import io.jsl.nyajsw.platform.ProcessTools;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Job objects for the application's processes (plan P6.4), through {@link ProcessApi}: a job with
 * {@code JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE}, so that Windows ends everything in it when its last
 * handle is closed, which happens at the latest when the wrapper's process ends, however it ends.
 * Processes a process in the job starts are in the job too. Since Windows 8 a job may be nested in
 * another, so this works also when the wrapper itself already runs in one.
 */
final class WindowsProcessTools implements ProcessTools {

    /** The exit code of the processes {@link ProcessJob#terminate()} ends, as {@code Process.destroyForcibly()} gives on Windows. */
    static final int TERMINATED = 1;

    private final ProcessApi api;

    WindowsProcessTools(ProcessApi api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    @Override
    public Optional<ProcessJob> createKillOnCloseJob() {
        KernelHandle job = call(api::createJob, "create a job object for the application");
        try {
            call(() -> {
                api.setKillOnClose(job);
                return null;
            }, "create a job object for the application");
        } catch (RuntimeException e) {
            api.close(job);
            throw e;
        }
        return Optional.of(new Job(job));
    }

    private final class Job implements ProcessJob {

        private final KernelHandle handle;
        private boolean closed;

        Job(KernelHandle handle) {
            this.handle = handle;
        }

        @Override
        public synchronized void add(long pid) {
            open();
            String what = "put process " + pid + " in the job object";
            KernelHandle process = call(() -> api.openProcessForJob(pid), what);
            try {
                call(() -> {
                    api.assign(handle, process);
                    return null;
                }, what);
            } finally {
                api.close(process);
            }
        }

        @Override
        public synchronized void terminate() {
            open();
            call(() -> {
                api.terminate(handle, TERMINATED);
                return null;
            }, "end the processes of the job object");
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                api.close(handle);
            }
        }

        private void open() {
            if (closed) {
                throw new IllegalStateException("the job object is closed");
            }
        }
    }

    /** {@code call}'s result; a failure says what was attempted, as does a native library that cannot be loaded. */
    private static <T> T call(Supplier<T> call, String what) {
        try {
            return call.get();
        } catch (PlatformException e) {
            throw new PlatformException("cannot " + what + ": " + e.getMessage(), e);
        } catch (LinkageError e) {
            throw NativeLibrary.cannotLoad(what, e);
        }
    }
}
