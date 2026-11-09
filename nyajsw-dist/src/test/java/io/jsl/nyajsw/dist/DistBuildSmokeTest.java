package io.jsl.nyajsw.dist;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

/**
 * Checks the build JVM and that sources are compiled as UTF-8, which the shipped scripts and
 * templates rely on.
 */
public class DistBuildSmokeTest {

    @Test
    public void buildRunsOnJava21OrLater() {
        assertTrue(Runtime.version().feature() >= 21, "build must run on Java 21+, got " + Runtime.version());
    }

    @Test
    public void sourcesAreCompiledAsUtf8() {
        String latinSmallLetterUWithDiaeresis = "ü";

        assertEquals(latinSmallLetterUWithDiaeresis.length(), 1);
        assertEquals(latinSmallLetterUWithDiaeresis.codePointAt(0), 0xFC);
    }
}
