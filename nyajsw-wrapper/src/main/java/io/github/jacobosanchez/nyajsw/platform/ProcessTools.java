package io.github.jacobosanchez.nyajsw.platform;

import java.util.Optional;

/** What the operating system offers for the application's processes beyond the JDK (plan §4.3, P6.4). */
public interface ProcessTools {

    /** No such help: the application's processes are ended through the JDK alone. */
    ProcessTools NONE = () -> Optional.empty();

    /**
     * A new {@link ProcessJob}: processes put in it, and every process they start from then on, are
     * ended by the operating system when the job is closed, also when the wrapper itself ends in any
     * way (killed, crashed), so that no application is left running unwatched. Empty where the
     * platform has nothing of the kind.
     *
     * @throws PlatformException when the operating system refuses to create one
     */
    Optional<ProcessJob> createKillOnCloseJob();
}
