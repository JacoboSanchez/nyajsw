package io.jsl.nyajsw.config;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class LogFileNamesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 7);

    @DataProvider
    public Object[][] names() {
        return new Object[][] {
            {"wrapper.log", "wrapper.log", "wrapper.log.1", "wrapper.log.12"},
            {"wrapper.ROLLNUM.log", "wrapper.log", "wrapper.1.log", "wrapper.12.log"},
            {"wrapper_ROLLNUM.log", "wrapper.log", "wrapper_1.log", "wrapper_12.log"},
            {"wrapper-ROLLNUM", "wrapper", "wrapper-1", "wrapper-12"},
            {"ROLLNUM-wrapper.log", "wrapper.log", "1-wrapper.log", "12-wrapper.log"},
            {"wrapperROLLNUM.log", "wrapper.log", "wrapper1.log", "wrapper12.log"},
            {"wrapper_YYYYMMDD.log", "wrapper_20261107.log", "wrapper_20261107.log.1", "wrapper_20261107.log.12"},
            {"w_YYYYMMDD.ROLLNUM.log", "w_20261107.log", "w_20261107.1.log", "w_20261107.12.log"},
        };
    }

    @Test(dataProvider = "names")
    public void theCurrentFileAndItsRolledCopies(String pattern, String current, String first, String twelfth) {
        Path dir = Path.of("logs");
        LogFileNames names = new LogFileNames(dir.resolve(pattern));

        assertEquals(names.current(DAY), dir.resolve(current));
        assertEquals(names.rolled(DAY, 1), dir.resolve(first));
        assertEquals(names.rolled(DAY, 12), dir.resolve(twelfth));
    }

    @Test
    public void theDateOfAFileIsReadFromTheNamesOfTheCurrentFileAndItsCopies() {
        LogFileNames numbered = new LogFileNames(Path.of("logs", "w_YYYYMMDD.ROLLNUM.log"));
        LogFileNames appended = new LogFileNames(Path.of("logs", "w_YYYYMMDD.log"));

        assertTrue(numbered.hasDate());
        assertEquals(numbered.dateOf(Path.of("logs", "w_20261107.log")), Optional.of(DAY));
        assertEquals(numbered.dateOf(Path.of("w_20261107.12.log")), Optional.of(DAY));
        assertEquals(appended.dateOf(Path.of("w_20261107.log")), Optional.of(DAY));
        assertEquals(appended.dateOf(Path.of("w_20261107.log.3")), Optional.of(DAY));
        for (String other : List.of("w_2026110.log", "w_20261107.log.x", "x_20261107.log", "w_20261399.log",
                "w_20261107.log.1.bak")) {
            assertEquals(appended.dateOf(Path.of(other)), Optional.empty(), other);
        }
        LogFileNames special = new LogFileNames(Path.of("w(1)+[x]_YYYYMMDD.log"));
        assertEquals(special.dateOf(Path.of("w(1)+[x]_20261107.log")), Optional.of(DAY));
        assertEquals(special.dateOf(Path.of("w1x_20261107.log")), Optional.empty(), "literals stay literal");
        LogFileNames numberFirst = new LogFileNames(Path.of("ROLLNUM_YYYYMMDD.log"));
        assertEquals(numberFirst.dateOf(Path.of("20261107.log")), Optional.of(DAY));
        assertEquals(numberFirst.dateOf(Path.of("3_20261107.log")), Optional.of(DAY));
        LogFileNames adjacent = new LogFileNames(Path.of("wROLLNUMYYYYMMDD.log"));
        assertEquals(adjacent.dateOf(Path.of("w1220261107.log")), Optional.of(DAY));
        assertFalse(new LogFileNames(Path.of("wrapper.log")).hasDate());
        assertEquals(new LogFileNames(Path.of("wrapper.log")).dateOf(Path.of("wrapper.log")), Optional.empty());
    }

    @Test
    public void tokensInDirectoriesAreLeftAsWritten() {
        LogFileNames names = new LogFileNames(Path.of("YYYYMMDD", "wrapper.log"));

        assertEquals(names.current(DAY), Path.of("YYYYMMDD", "wrapper.log"));
    }

    @Test
    public void aNameThatIsOnlyTheNumberTokenOrHasItTwiceIsRejected() {
        expectThrows(IllegalArgumentException.class, () -> new LogFileNames(Path.of("logs", "ROLLNUM")));
        expectThrows(IllegalArgumentException.class, () -> new LogFileNames(Path.of("logs", "_ROLLNUM")));
        expectThrows(IllegalArgumentException.class, () -> new LogFileNames(Path.of("logs", "a_ROLLNUM_ROLLNUM.log")));
    }

    @Test
    public void copiesAreNumberedFromOne() {
        expectThrows(IllegalArgumentException.class, () -> new LogFileNames(Path.of("w.log")).rolled(DAY, 0));
    }
}
