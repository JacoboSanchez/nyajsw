package io.jsl.nyajsw.platform;

/**
 * What the wrapper needs from the operating system it runs on, behind one interface so that the
 * core never asks which one it is (plan §4.3, AGENTS.md §1). {@link Platforms} chooses the
 * implementation for the host.
 *
 * <p>Building a platform or its controller loads no native code: that waits for the first
 * operation, so the configuration can set where native libraries are unpacked first
 * ({@code jna.tmpdir} from {@code wrapper.tmp.path}, plan §4.4).
 */
public interface Platform {

    /** The platform's name, for messages. */
    String name();

    /** The service commands of this host; on a host without services, every operation fails. */
    ServiceController serviceController();

    /** Runs this process as a service ({@code --service}); on a host without services, it fails. */
    ServiceHost serviceHost();

    /** Help with the application's processes; {@link ProcessTools#NONE} where there is none. */
    ProcessTools processTools();
}
