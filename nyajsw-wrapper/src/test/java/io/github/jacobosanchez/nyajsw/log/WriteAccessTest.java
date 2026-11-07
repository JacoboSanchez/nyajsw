package io.github.jacobosanchez.nyajsw.log;

import static org.testng.Assert.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;

import org.testng.annotations.Test;

public class WriteAccessTest {

    @Test
    public void aDeniedAccessNamesTheAccountAndTheKey() {
        String hint = "; the wrapper runs as '" + System.getProperty("user.name") + "', which may not write there:"
                + " give it write access, or change wrapper.logfile";

        assertEquals(WriteAccess.hint(new AccessDeniedException("C:\\logs\\wrapper.log"), "wrapper.logfile"), hint);
        assertEquals(WriteAccess.hint(new UncheckedIOException(new AccessDeniedException("x")), "wrapper.logfile"),
                hint, "also as the cause");
    }

    @Test
    public void anyOtherFailureHasNoHint() {
        assertEquals(WriteAccess.hint(new NoSuchFileException("x"), "wrapper.pidfile"), "");
        assertEquals(WriteAccess.hint(new IOException("disk full"), "wrapper.pidfile"), "");
        assertEquals(WriteAccess.hint(null, "wrapper.pidfile"), "");
    }
}
