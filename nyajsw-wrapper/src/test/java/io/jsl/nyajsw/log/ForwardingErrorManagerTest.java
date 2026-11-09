package io.jsl.nyajsw.log;

import static org.testng.Assert.assertEquals;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.ErrorManager;

import org.testng.annotations.Test;

public class ForwardingErrorManagerTest {

    @Test
    public void aRepeatedFailureIsHeldBackForAWhileThenShownWithItsCount() {
        List<String> seen = new ArrayList<>();
        long[] nanos = {0};
        ErrorManager manager = new ForwardingErrorManager(seen::add, () -> nanos[0]);
        String full = "cannot write the log file w.log: disk full";

        manager.error(full, null, ErrorManager.WRITE_FAILURE);
        manager.error(full, null, ErrorManager.WRITE_FAILURE);
        manager.error(full, null, ErrorManager.WRITE_FAILURE);
        nanos[0] += ForwardingErrorManager.REPEAT_AFTER.toNanos();
        manager.error(full, null, ErrorManager.WRITE_FAILURE);
        manager.error("cannot roll the log file w.log", null, ErrorManager.GENERIC_FAILURE);
        manager.error(full, null, ErrorManager.WRITE_FAILURE); // a different failure came between

        nanos[0] += Duration.ofDays(4).toNanos(); // the disk fills again days later
        manager.error(full, null, ErrorManager.WRITE_FAILURE);

        assertEquals(seen, List.of(full, full + " (2 more times since it was last shown)",
                "cannot roll the log file w.log", full, full));
    }

    @Test
    public void anExceptionIsShownByItsClassOnly() {
        List<String> seen = new ArrayList<>();
        ErrorManager manager = new ForwardingErrorManager(seen::add);

        manager.error(null, new IllegalStateException("C:\\secret\\w.log"), ErrorManager.FORMAT_FAILURE);
        manager.error("closing", new IOException("C:\\secret\\w.log"), ErrorManager.CLOSE_FAILURE);

        assertEquals(seen, List.of("a log record could not be written (java.lang.IllegalStateException)",
                "closing (java.io.IOException)"));
    }

    @Test
    public void aTargetThatFailsDoesNotFailTheSink() {
        ErrorManager manager = new ForwardingErrorManager(message -> {
            throw new IllegalStateException("console gone");
        });

        manager.error("x", null, ErrorManager.GENERIC_FAILURE); // returns quietly
    }
}
