package io.jsl.nyajsw.config;

import static io.jsl.nyajsw.config.LineFormat.Field.LEVEL;
import static io.jsl.nyajsw.config.LineFormat.Field.MESSAGE;
import static io.jsl.nyajsw.config.LineFormat.Field.MILLIS;
import static io.jsl.nyajsw.config.LineFormat.Field.PREFIX;
import static io.jsl.nyajsw.config.LineFormat.Field.THREAD;
import static io.jsl.nyajsw.config.LineFormat.Field.TIME;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.util.List;
import java.util.Optional;

import org.testng.annotations.Test;

public class LineFormatTest {

    private static LineFormat parsed(String text) {
        LineFormat.Parsed parsed = LineFormat.parse(text);
        assertEquals(parsed.problems(), List.of(), text);
        return parsed.format().orElseThrow();
    }

    @Test
    public void everyLetterIsAFieldInTheOrderWrittenInEitherCase() {
        assertEquals(parsed("LPTM").fields(), List.of(LEVEL, PREFIX, TIME, MESSAGE));
        assertEquals(parsed("mzdl").fields(), List.of(MESSAGE, MILLIS, THREAD, LEVEL));
        assertEquals(parsed("PP").fields(), List.of(PREFIX, PREFIX));
        assertEquals(parsed("P M").fields(), List.of(PREFIX, MESSAGE), "spaces are ignored");
        assertEquals(parsed("mzdl").toString(), "MZDL");
    }

    @Test
    public void linesEndWithThePlatformsSeparatorUnlessTheFormatEndsWithAnEscapedOne() {
        assertEquals(parsed("PM").lineSeparator(), System.lineSeparator());
        assertEquals(parsed("PM\\n").lineSeparator(), "\n");
        assertEquals(parsed("PM\\r\\n").lineSeparator(), "\r\n");
        assertEquals(parsed("PM\\r\\n").fields(), List.of(PREFIX, MESSAGE));
    }

    @Test
    public void theConsoleDefaultIsTheRegistrysDefault() {
        assertEquals(LineFormat.CONSOLE_DEFAULT.fields(), List.of(PREFIX, MESSAGE));
        assertEquals(LineFormat.CONSOLE_DEFAULT.lineSeparator(), System.lineSeparator());
    }

    @Test
    public void otherCharactersAreSkippedAndReported() {
        LineFormat.Parsed parsed = LineFormat.parse("P-M\tX");

        assertEquals(parsed.format().map(LineFormat::toString), Optional.of("PM"));
        assertEquals(parsed.problems(), List.of(
                "'-' is not a format letter (L P D T Z M); skipped",
                "'\\u0009' is not a format letter (L P D T Z M); skipped",
                "'X' is not a format letter (L P D T Z M); skipped"));
    }

    @Test
    public void aFormatWithoutLettersHasNoFormat() {
        LineFormat.Parsed parsed = LineFormat.parse("?\\n");

        assertEquals(parsed.format(), Optional.empty());
        assertTrue(parsed.problems().contains("no format letter, so nothing is written there"),
                parsed.problems().toString());
    }

    @Test
    public void aLineFormatNeedsAField() {
        expectThrows(IllegalArgumentException.class, () -> new LineFormat(List.of(), "\n"));
    }
}
