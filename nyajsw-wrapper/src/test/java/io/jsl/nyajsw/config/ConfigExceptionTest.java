package io.jsl.nyajsw.config;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.util.List;
import java.util.Optional;

import org.testng.annotations.Test;

public class ConfigExceptionTest {

    private final ConfigException first = new ConfigException(new ConfigLocation("a.conf", 1), "first");
    private final ConfigException second = new ConfigException("second");

    @Test
    public void singleProblemIsItsOwnProblemList() {
        assertEquals(first.problems(), List.of(first));
        assertSame(ConfigException.aggregate(List.of(first)), first);
        assertEquals(first.location(), Optional.of(new ConfigLocation("a.conf", 1)));
    }

    @Test
    public void severalProblemsAreListedAndFlattened() {
        ConfigException third = new ConfigException("third");
        ConfigException two = ConfigException.aggregate(List.of(first, second));

        ConfigException all = ConfigException.aggregate(List.of(two, third));

        assertEquals(all.problems(), List.of(first, second, third));
        assertEquals(all.location(), Optional.empty());
        String newline = System.lineSeparator();
        assertEquals(all.getMessage(), "3 configuration errors:" + newline + "  a.conf:1: first" + newline
                + "  second" + newline + "  third");
    }

    @Test
    public void nothingToAggregateIsAProgrammingError() {
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class,
                () -> ConfigException.aggregate(List.of()));
        assertTrue(e.getMessage().contains("no problems"));
    }
}
