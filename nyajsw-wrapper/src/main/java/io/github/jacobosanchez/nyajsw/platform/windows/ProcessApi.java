package io.github.jacobosanchez.nyajsw.platform.windows;

import io.github.jacobosanchez.nyajsw.platform.PlatformException;

/**
 * The {@code kernel32} calls the wrapper makes about the application's processes, one method per
 * Win32 call, in plain Java types so that {@link WindowsProcessTools} can be tested with mocks (plan
 * §4.4, AGENTS.md §4.5). {@link JnaProcessApi} is the implementation; every method but {@link
 * #close} throws a {@link PlatformException} with the Win32 error code's text when the call fails.
 */
interface ProcessApi {

    /** {@code CreateJobObject} without a name, with the default security. */
    KernelHandle createJob();

    /** {@code SetInformationJobObject} with the extended limits {@code JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE} alone. */
    void setKillOnClose(KernelHandle job);

    /** {@code OpenProcess} with the access {@code AssignProcessToJobObject} needs. */
    KernelHandle openProcessForJob(long pid);

    /** {@code AssignProcessToJobObject}. */
    void assign(KernelHandle job, KernelHandle process);

    /** {@code TerminateJobObject}: every process in the job ends with {@code exitCode}. */
    void terminate(KernelHandle job, int exitCode);

    /** {@code CloseHandle}; a failure is ignored, since it can only leak the handle. */
    void close(KernelHandle handle);
}
