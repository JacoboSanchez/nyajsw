package io.github.jacobosanchez.nyajsw.platform.windows;

import static org.testng.Assert.assertEquals;

import java.time.Duration;

import org.testng.annotations.Test;

/** What {@link JnaScmApi} computes itself; its calls are tested against the real SCM (P5.6). */
public class JnaScmApiTest {

    @Test
    public void aTimeoutIsAPositiveDwordOfMilliseconds() {
        assertEquals(JnaScmApi.dword(Duration.ofSeconds(65)), 65_000);
        assertEquals(JnaScmApi.dword(Duration.ofDays(100)), Integer.MAX_VALUE);
        assertEquals(JnaScmApi.dword(Duration.ofMillis(-1)), 0);
    }
}
