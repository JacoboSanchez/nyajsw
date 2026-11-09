package io.jsl.nyajsw.config;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.util.List;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class StatusRangesTest {

    @Test
    public void codesAndRangesSeparatedByCommas() {
        StatusRanges ranges = StatusRanges.parse(" 200 - 299 ,304,401");

        assertEquals(ranges.ranges(), List.of(new StatusRanges.Range(200, 299), new StatusRanges.Range(304, 304),
                new StatusRanges.Range(401, 401)));
        assertTrue(ranges.contains(200));
        assertTrue(ranges.contains(299));
        assertTrue(ranges.contains(304));
        assertFalse(ranges.contains(300));
        assertFalse(ranges.contains(500));
        assertEquals(ranges.toString(), "200-299,304,401");
    }

    @DataProvider
    public Object[][] invalid() {
        return new Object[][] {{""}, {"ok"}, {"200-"}, {"200,,204"}, {"2xx"}, {"099"}, {"600"}, {"399-200"},
                {"200-600"}, {"1000"}};
    }

    @Test(dataProvider = "invalid")
    public void anythingElseIsRefusedWithAFixedReason(String text) {
        IllegalArgumentException error = expectThrows(IllegalArgumentException.class, () -> StatusRanges.parse(text));

        assertTrue(List.of("expected HTTP status codes or ranges such as 200-399 or 200,204",
                "HTTP status codes go from 100 to 599, the lower end first").contains(error.getMessage()),
                error.getMessage());
    }

    @Test
    public void aRangeGoesUpWithinTheStatusCodes() {
        expectThrows(IllegalArgumentException.class, () -> new StatusRanges.Range(300, 200));
        expectThrows(IllegalArgumentException.class, () -> new StatusRanges.Range(99, 200));
        expectThrows(IllegalArgumentException.class, () -> new StatusRanges(List.of()));
    }
}
