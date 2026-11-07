package io.github.jacobosanchez.nyajsw.platform;

/**
 * A group of processes that ends together ({@link ProcessTools#createKillOnCloseJob}). A process
 * started before it was added stays outside, as does anything it had started by then.
 */
public interface ProcessJob extends AutoCloseable {

    /**
     * Puts the running process {@code pid} in the job.
     *
     * @throws PlatformException   when the operating system refuses, such as for a process that has
     *                              ended already
     * @throws IllegalStateException once the job is closed
     */
    void add(long pid);

    /**
     * Ends every process in the job at once, without waiting for them.
     *
     * @throws PlatformException   when the operating system refuses
     * @throws IllegalStateException once the job is closed
     */
    void terminate();

    /** Closes the job, which ends the processes still in it; more than one call is harmless. */
    @Override
    void close();
}
