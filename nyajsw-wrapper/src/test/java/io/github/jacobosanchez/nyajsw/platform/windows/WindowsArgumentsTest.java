package io.github.jacobosanchez.nyajsw.platform.windows;

import static org.testng.Assert.assertEquals;

import java.util.List;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class WindowsArgumentsTest {

    @DataProvider
    public Object[][] arguments() {
        return new Object[][] {
            {"plain", "plain"},
            {"C:\\Program Files\\Java\\bin\\javaw.exe", "\"C:\\Program Files\\Java\\bin\\javaw.exe\""},
            {"", "\"\""},
            {"tab\there", "\"tab\there\""},
            {"say \"hi\"", "\"say \\\"hi\\\"\""},
            {"a\\\"b", "\"a\\\\\\\"b\""}, // a backslash before a quote is doubled, the quote escaped
            {"C:\\dir with space\\", "\"C:\\dir with space\\\\\""}, // a trailing backslash is doubled
            {"C:\\no\\space\\", "C:\\no\\space\\"}, // unquoted, so backslashes stay as they are
            {"a\\\\b c", "\"a\\\\b c\""}, // backslashes not before a quote stay as they are
        };
    }

    @Test(dataProvider = "arguments")
    public void eachArgumentIsQuotedSoThatTheCRuntimeSplitsItBack(String argument, String quoted) {
        assertEquals(WindowsArguments.quote(argument), quoted);
    }

    @Test
    public void argumentsAreSeparatedBySingleSpaces() {
        assertEquals(WindowsArguments.join(List.of("C:\\j a\\javaw.exe", "-cp", "x;y", "--service", "C:\\c d\\w.conf")),
                "\"C:\\j a\\javaw.exe\" -cp x;y --service \"C:\\c d\\w.conf\"");
    }
}
