package io.jsl.nyajsw.config;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class IndexOrderTest {

    @Test
    public void numbersCompareByValueAndComeBeforeNames() {
        List<String> indices = new ArrayList<>(List.of("ab12", "lib", "10", "ab5", "9", "5", "1"));

        indices.sort(IndexOrder.NATURAL);

        assertEquals(indices, List.of("1", "5", "9", "10", "ab5", "ab12", "lib"));
    }

    @Test
    public void dottedIndicesCompareRunByRun() {
        List<String> indices = new ArrayList<>(List.of("3", "2.10", "2.2", "2"));

        indices.sort(IndexOrder.NATURAL);

        assertEquals(indices, List.of("2", "2.2", "2.10", "3"));
    }

    @DataProvider
    public Object[][] sameIndex() {
        return new Object[][] {
                {"7", "07"},
                {"ab7", "ab007"},
                {"2.1", "2.01"},
                {"0", "000"},
        };
    }

    @Test(dataProvider = "sameIndex")
    public void leadingZerosDoNotMakeAnotherIndex(String a, String b) {
        assertEquals(IndexOrder.NATURAL.compare(a, b), 0);
        assertEquals(IndexOrder.normalise(a), IndexOrder.normalise(b));
    }

    @Test
    public void namesCompareCaseSensitivelyAsKeysDo() {
        assertTrue(IndexOrder.NATURAL.compare("B", "a") < 0);
        assertTrue(IndexOrder.NATURAL.compare("a", "a1") < 0);
    }

    @Test
    public void hugeNumbersNeedNoParsing() {
        assertTrue(IndexOrder.NATURAL.compare("99999999999999999999", "100000000000000000000") < 0);
    }
}
