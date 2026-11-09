package io.jsl.nyajsw.launch;

import static org.testng.Assert.assertEquals;

import java.util.List;

import org.testng.annotations.Test;

public class OutputLinesTest {

    @Test
    public void linesAreNumberedAsTheyComeAndReadFromANumberOn() {
        OutputLines output = new OutputLines();
        output.add("a");
        output.add("b");

        OutputLines.Page all = output.since(0);
        assertEquals(all, new OutputLines.Page(List.of("a", "b"), 2, 0));
        output.add("c");
        assertEquals(output.since(all.next()), new OutputLines.Page(List.of("c"), 3, 0));
        assertEquals(output.since(3), new OutputLines.Page(List.of(), 3, 0));
    }

    @Test
    public void onlyTheLatestLinesAreKeptAndAReaderBehindIsToldHowManyItMissed() {
        OutputLines output = new OutputLines();
        for (int i = 0; i < OutputLines.MAX_LINES + 5; i++) {
            output.add("line " + i);
        }

        OutputLines.Page page = output.since(0);
        assertEquals(page.skipped(), 5);
        assertEquals(page.lines().size(), OutputLines.MAX_LINES);
        assertEquals(page.lines().get(0), "line 5");
        assertEquals(page.next(), OutputLines.MAX_LINES + 5);
        assertEquals(output.since(3).skipped(), 2);
    }

    @Test
    public void longLinesAreKeptWithinTheCharacterLimitButTheLatestStays() {
        OutputLines output = new OutputLines();
        String half = "x".repeat((int) (OutputLines.MAX_CHARS / 2));
        output.add(half);
        output.add(half);
        output.add("tail");

        OutputLines.Page page = output.since(0);
        assertEquals(page.skipped(), 1);
        assertEquals(page.lines().get(page.lines().size() - 1), "tail");
        OutputLines huge = new OutputLines();
        huge.add("y".repeat((int) OutputLines.MAX_CHARS + 1));
        assertEquals(huge.since(0).lines().size(), 1, "a single line always stays");
    }
}
