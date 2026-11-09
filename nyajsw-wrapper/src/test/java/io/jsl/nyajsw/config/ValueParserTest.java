package io.jsl.nyajsw.config;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.time.Duration;
import java.util.List;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class ValueParserTest {

    @Test
    public void aCommandIsSplitAtBlanksOutsideDoubleQuotes() {
        assertEquals(ValueParser.parseCommand("  check  --port 8080 "), List.of("check", "--port", "8080"));
        assertEquals(ValueParser.parseCommand("\"C:\\Program Files\\x.exe\" -v"),
                List.of("C:\\Program Files\\x.exe", "-v"));
        assertEquals(ValueParser.parseCommand("a\"b c\"d \"\" e"), List.of("ab cd", "", "e"));
        assertEquals(ValueParser.parseCommand("tab\tand\nnewline"), List.of("tab", "and", "newline"));
        assertEquals(ValueParser.parseCommand("   "), List.of());
        IllegalArgumentException open = expectThrows(IllegalArgumentException.class,
                () -> ValueParser.parseCommand("check \"s3cr3t"));
        assertEquals(open.getMessage(), "a double quote is not closed");
    }

    @Test
    public void integers() {
        assertEquals(ValueParser.parseInteger("42"), 42);
        assertEquals(ValueParser.parseInteger("-7"), -7);
        assertEquals(ValueParser.parseInteger("+3"), 3);
        assertInvalid(() -> ValueParser.parseInteger("4.2"), "whole number");
        assertInvalid(() -> ValueParser.parseInteger("12MB"), "whole number");
        assertInvalid(() -> ValueParser.parseInteger("99999999999"), "out of range");
    }

    @DataProvider
    public Object[][] booleans() {
        return new Object[][] {
                {"true", true}, {"TRUE", true}, {"yes", true}, {"On", true},
                {"false", false}, {"No", false}, {"off", false},
        };
    }

    @Test(dataProvider = "booleans")
    public void booleansAcceptCommonsConfigurationSpellings(String value, boolean expected) {
        assertEquals(ValueParser.parseBoolean(value), expected);
    }

    @Test
    public void invalidBoolean() {
        assertInvalid(() -> ValueParser.parseBoolean("1"), "true or false");
    }

    @Test
    public void durationsAreNonNegativeSeconds() {
        assertEquals(ValueParser.parseDuration("30"), Duration.ofSeconds(30));
        assertEquals(ValueParser.parseDuration("0"), Duration.ZERO);
        assertInvalid(() -> ValueParser.parseDuration("-1"), "0 or more");
        assertInvalid(() -> ValueParser.parseDuration("1.5"), "seconds");
        assertInvalid(() -> ValueParser.parseDuration("99999999999999999999"), "out of range");
    }

    @Test
    public void sizesUseBinaryMultiples() {
        assertEquals(ValueParser.parseSize("512"), 512L);
        assertEquals(ValueParser.parseSize("10k"), 10L * 1024);
        assertEquals(ValueParser.parseSize("10M"), 10L * 1024 * 1024);
        assertEquals(ValueParser.parseSize("1G"), 1L << 30);
        assertInvalid(() -> ValueParser.parseSize("10MB"), "size such as");
        assertInvalid(() -> ValueParser.parseSize("-1"), "size such as");
        assertInvalid(() -> ValueParser.parseSize("9999999999999G"), "out of range");
        assertInvalid(() -> ValueParser.parseSize("99999999999999999999"), "out of range");
    }

    @Test
    public void enumerationsReturnTheCanonicalValue() {
        assertEquals(ValueParser.parseEnum(ConfigKeys.NTSERVICE_STARTTYPE, "delay_start"), "DELAYED_AUTO_START");
        assertEquals(ValueParser.parseEnum(ConfigKeys.CONTROL, "loose"), "LOOSE");
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class,
                () -> ValueParser.parseEnum(ConfigKeys.NTSERVICE_STARTTYPE, "SOMETIMES"));
        assertEquals(e.getMessage(), "expected one of AUTO_START, DEMAND_START, DELAYED_AUTO_START, DISABLED"
                + " (or DELAY_START)");
        assertInvalid(() -> ValueParser.parseEnum(ConfigKeys.JAVA_COMMAND, "x"), "not an enumeration");
    }

    @DataProvider
    public Object[][] paths() {
        return new Object[][] {
                {"C:\\app\\lib", "C:\\app\\lib"},
                {"C:\\\\app\\\\lib", "C:\\app\\lib"},
                {"C:/app/lib", "C:/app/lib"},
                {"\\\\server\\share\\dir", "\\\\server\\share\\dir"},
                {"\\\\\\\\server\\\\share", "\\\\server\\share"},
                {"lib", "lib"},
                {"C:\\app\\\\", "C:\\app\\"},
                {"\\\\server\\share\\\\", "\\\\server\\share\\"},
                {"\\\\server\\share\\app\\\\", "\\\\server\\share\\app\\"},
                {"\\\\server\\share\\\\dir", "\\\\server\\share\\dir"},
                {"\\\\?\\C:\\app\\\\", "\\\\?\\C:\\app\\"},
                {"\\\\\\\\fs01\\\\apps\\\\svc\\\\", "\\\\fs01\\apps\\svc\\"},
                {"\\\\", "\\"},
        };
    }

    @Test(dataProvider = "paths")
    public void pathsUndoDoubledBackslashesButKeepLiteralUncPaths(String value, String expected) {
        assertEquals(ValueParser.normalisePath(value), expected);
    }

    @Test
    public void listsAreTrimmedWithoutEmptyItems() {
        assertEquals(ValueParser.parseList(" agent , terminate,,\u3000"), List.of("agent", "terminate"));
        assertEquals(ValueParser.parseList(""), List.of());
    }

    @Test
    public void parseDispatchesOnTheDeclaredType() {
        assertEquals(ValueParser.parse(ConfigKeys.STARTUP_TIMEOUT, "45"), Duration.ofSeconds(45));
        assertEquals(ValueParser.parse(ConfigKeys.MAX_FAILED_INVOCATIONS, "3"), 3);
        assertEquals(ValueParser.parse(ConfigKeys.DEBUG, "yes"), true);
        assertEquals(ValueParser.parse(ConfigKeys.LOGFILE_MAXSIZE, "1K"), 1024L);
        assertEquals(ValueParser.parse(ConfigKeys.CONTROL, "tight"), "TIGHT");
        assertEquals(ValueParser.parse(ConfigKeys.WORKING_DIR, "C:\\\\x"), "C:\\x");
        assertEquals(ValueParser.parse(ConfigKeys.STOP_STRATEGY, "a,b"), List.of("a", "b"));
        assertEquals(ValueParser.parse(ConfigKeys.JAVA_APP_MAINCLASS, " x "), " x ");
    }

    @Test
    public void reasonsAndCausesNeverContainTheValue() {
        for (org.testng.Assert.ThrowingRunnable call : List.<org.testng.Assert.ThrowingRunnable>of(
                () -> ValueParser.parseInteger("12345678901"),
                () -> ValueParser.parseDuration("123456789012345678901"),
                () -> ValueParser.parseSize("123456789012345678901"),
                () -> ValueParser.parseBoolean("12345678901"))) {
            IllegalArgumentException e = expectThrows(IllegalArgumentException.class, call);
            assertFalse(e.getMessage().contains("12345678901"), e.getMessage());
            assertEquals(e.getCause(), null);
        }
    }

    @Test
    public void onlyAsciiDigitsAreNumbers() {
        assertInvalid(() -> ValueParser.parseInteger("\u0661\u0662"), "whole number");
        assertInvalid(() -> ValueParser.parseDuration("\u0661"), "seconds");
    }

    @Test
    public void unsetMeansEmptyForTypedKeysAndNoItemsForLists() {
        assertTrue(ValueParser.isUnset(ConfigKeys.STARTUP_TIMEOUT, ""));
        assertFalse(ValueParser.isUnset(ConfigKeys.STARTUP_TIMEOUT, "0"));
        assertTrue(ValueParser.isUnset(ConfigKeys.STOP_STRATEGY, " , "));
        assertFalse(ValueParser.isUnset(ConfigKeys.STOP_STRATEGY, "terminate"));
        assertFalse(ValueParser.isUnset(ConfigKeys.NTSERVICE_DESCRIPTION, ""));
    }

    @Test
    public void scalarParsingRejectsEnumerations() {
        assertEquals(ValueParser.parseScalar(KeyType.INTEGER, "5"), 5);
        assertInvalid(() -> ValueParser.parseScalar(KeyType.ENUM, "A"), "belong to a declaration");
    }

    private static void assertInvalid(org.testng.Assert.ThrowingRunnable call, String reason) {
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, call);
        assertTrue(e.getMessage().contains(reason), e.getMessage());
    }
}
