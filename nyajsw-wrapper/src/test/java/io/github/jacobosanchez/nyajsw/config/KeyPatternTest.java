package io.github.jacobosanchez.nyajsw.config;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.util.List;
import java.util.Optional;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class KeyPatternTest {

    @Test
    public void exactNameMatchesOnlyItselfCaseSensitively() {
        KeyPattern pattern = KeyPattern.of("wrapper.java.command");

        assertTrue(pattern.isExact());
        assertTrue(pattern.matches("wrapper.java.command"));
        assertFalse(pattern.matches("wrapper.java.command.javaw"));
        assertFalse(pattern.matches("wrapper.java.Command"));
        assertFalse(pattern.matches("xwrapper.java.command"));
        assertEquals(pattern.example(), "wrapper.java.command");
        assertEquals(pattern.literalLength(), "wrapper.java.command".length());
    }

    @Test
    public void indexPlaceholderMatchesDigitsOnly() {
        KeyPattern pattern = KeyPattern.of("wrapper.java.additional.<n>");

        assertFalse(pattern.isExact());
        assertTrue(pattern.matches("wrapper.java.additional.1"));
        assertTrue(pattern.matches("wrapper.java.additional.007"));
        assertTrue(pattern.matches("wrapper.java.additional.250"));
        assertFalse(pattern.matches("wrapper.java.additional."));
        assertFalse(pattern.matches("wrapper.java.additional.x"));
        assertFalse(pattern.matches("wrapper.java.additional.1.stripquotes"));
        assertFalse(pattern.matches("wrapper.java.additional"));
        assertEquals(pattern.example(), "wrapper.java.additional.1");
    }

    @Test
    public void exitCodePlaceholderMatchesSignedIntegersOnly() {
        KeyPattern pattern = KeyPattern.of("wrapper.on_exit.<code>");

        assertTrue(pattern.matches("wrapper.on_exit.0"));
        assertTrue(pattern.matches("wrapper.on_exit.143"));
        assertTrue(pattern.matches("wrapper.on_exit.-1"));
        assertFalse(pattern.matches("wrapper.on_exit.defualt"));
        assertFalse(pattern.matches("wrapper.on_exit.-"));
        assertFalse(pattern.matches("wrapper.on_exit.1.5"));
        assertEquals(pattern.example(), "wrapper.on_exit.1");
        assertEquals(pattern.numericPlaceholders(), 1);
    }

    @Test
    public void namedPlaceholderMatchesNonEmptySegmentsIncludingDots() {
        KeyPattern pattern = KeyPattern.of("wrapper.app.env.<variable>");

        assertTrue(pattern.matches("wrapper.app.env.PATH"));
        assertTrue(pattern.matches("wrapper.app.env.my.var"));
        assertFalse(pattern.matches("wrapper.app.env."));
        assertFalse(pattern.matches("wrapper.app.env..x"));
        assertFalse(pattern.matches("wrapper.app.env.x."));
        assertFalse(pattern.matches("wrapper.app.envPATH"));
        assertEquals(pattern.example(), "wrapper.app.env.variable");
        assertEquals(pattern.numericPlaceholders(), 0);
    }

    @Test
    public void mostSpecificFirstRanksLiteralsThenNumericPlaceholders() {
        KeyPattern longer = KeyPattern.of("wrapper.filter.script.<n>.args");
        KeyPattern index = KeyPattern.of("wrapper.x.<n>");
        KeyPattern free = KeyPattern.of("wrapper.x.<name>");

        assertTrue(KeyPattern.MOST_SPECIFIC_FIRST.compare(longer, index) < 0);
        assertTrue(KeyPattern.MOST_SPECIFIC_FIRST.compare(index, free) < 0);
        assertEquals(KeyPattern.MOST_SPECIFIC_FIRST.compare(free, KeyPattern.of("wrapper.y.<other>")), 0);
    }

    @Test
    public void commonExampleFindsOverlapsBetweenTemplates() {
        assertEquals(KeyPattern.of("wrapper.a.<x>.c").commonExample(KeyPattern.of("wrapper.a.b.<y>")),
                Optional.of("wrapper.a.b.c"));
        assertEquals(KeyPattern.of("wrapper.readiness.<type>.timeout")
                        .commonExample(KeyPattern.of("wrapper.readiness.http.<option>")),
                Optional.of("wrapper.readiness.http.timeout"));
        assertEquals(KeyPattern.of("wrapper.x.<n>").commonExample(KeyPattern.of("wrapper.x.<name>")),
                Optional.of("wrapper.x.1"));
        assertTrue(KeyPattern.of("wrapper.a.<n>").commonExample(KeyPattern.of("wrapper.b.<n>")).isEmpty());
        assertTrue(KeyPattern.of("wrapper.a.<n>").commonExample(KeyPattern.of("wrapper.a.b")).isEmpty());
        assertTrue(KeyPattern.of("wrapper.a.<n>").commonExample(KeyPattern.of("wrapper.a.<n>.b")).isEmpty());
    }

    @Test
    public void placeholderInTheMiddle() {
        KeyPattern pattern = KeyPattern.of("wrapper.filter.script.<n>.args");

        assertTrue(pattern.matches("wrapper.filter.script.3.args"));
        assertFalse(pattern.matches("wrapper.filter.script.3"));
        assertFalse(pattern.matches("wrapper.filter.script.3.argsx"));
        assertEquals(pattern.example(), "wrapper.filter.script.1.args");
        assertEquals(pattern.literalLength(), "wrapper.filter.script..args".length());
    }

    @Test
    public void literalSegmentsMayContainHyphens() {
        KeyPattern pattern = KeyPattern.of("wrapper.filter.trigger-regex.<n>");

        assertTrue(pattern.matches("wrapper.filter.trigger-regex.2"));
        assertFalse(pattern.matches("wrapper.filter.trigger.2"));
        assertEquals(pattern.example(), "wrapper.filter.trigger-regex.1");
    }

    @Test
    public void regexCharactersInLiteralsAreNotSpecial() {
        KeyPattern pattern = KeyPattern.of("wrapper.a_b.<n>");

        assertFalse(pattern.matches("wrapperXa_b.1"));
    }

    @DataProvider
    public Object[][] invalidTemplates() {
        return new Object[][] {
                {""},
                {"wrapper..x"},
                {".wrapper"},
                {"wrapper."},
                {"<n>.wrapper"},
                {"wrapper.x<n>"},
                {"wrapper.<n>x"},
                {"wrapper.<N>"},
                {"wrapper.<>"},
                {"wrapper.a+b"},
                {"wrapper.a b"},
                {"wrapper.*"},
        };
    }

    @Test(dataProvider = "invalidTemplates")
    public void rejectsInvalidTemplates(String template) {
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> KeyPattern.of(template));

        assertTrue(e.getMessage().contains("'" + template + "'"), e.getMessage());
    }

    @Test
    public void capturesReturnsPlaceholderValuesInTemplateOrder() {
        assertEquals(KeyPattern.of("wrapper.java.additional.<n>").captures("wrapper.java.additional.12"),
                Optional.of(List.of("12")));
        assertEquals(KeyPattern.of("wrapper.filter.script.<n>.args").captures("wrapper.filter.script.3.args"),
                Optional.of(List.of("3")));
        assertEquals(KeyPattern.of("wrapper.app.env.<variable>").captures("wrapper.app.env.my.var"),
                Optional.of(List.of("my.var")));
        assertEquals(KeyPattern.of("wrapper.on_exit.<code>").captures("wrapper.on_exit.-1"),
                Optional.of(List.of("-1")));
        assertEquals(KeyPattern.of("wrapper.x.<a>.y.<n>").captures("wrapper.x.p.q.y.2"),
                Optional.of(List.of("p.q", "2")));
        assertEquals(KeyPattern.of("wrapper.java.command").captures("wrapper.java.command"),
                Optional.of(List.of()));
        assertEquals(KeyPattern.of("wrapper.java.command").captures("wrapper.java.commandx"), Optional.empty());
        assertEquals(KeyPattern.of("wrapper.x.<n>").captures("wrapper.x.y"), Optional.empty());
    }

    @Test
    public void labelsAndNumericPositions() {
        KeyPattern pattern = KeyPattern.of("wrapper.x.<name>.<n>.<code>");

        assertEquals(pattern.labels(), List.of("name", "n", "code"));
        assertFalse(pattern.isNumeric(0));
        assertTrue(pattern.isNumeric(1));
        assertTrue(pattern.isNumeric(2));
        assertTrue(KeyPattern.of("wrapper.x").labels().isEmpty());
    }

    @Test
    public void equalityIsByTemplate() {
        assertEquals(KeyPattern.of("wrapper.x.<n>"), KeyPattern.of("wrapper.x.<n>"));
        assertEquals(KeyPattern.of("wrapper.x.<n>").hashCode(), KeyPattern.of("wrapper.x.<n>").hashCode());
        assertNotEquals(KeyPattern.of("wrapper.x.<n>"), KeyPattern.of("wrapper.x.<name>"));
        assertEquals(KeyPattern.of("wrapper.x.<n>").toString(), "wrapper.x.<n>");
    }
}
