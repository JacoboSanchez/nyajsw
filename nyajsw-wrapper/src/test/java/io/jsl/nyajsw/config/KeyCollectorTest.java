package io.jsl.nyajsw.config;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.Test;

public class KeyCollectorTest {

    private final KeyCollector collector = new KeyCollector(ConfigKeys.registry());

    @Test
    public void indexedKeysSortNumericallyWithGaps() {
        KeyCollector.Collected collected = collector.collect(ConfigKeys.JAVA_ADDITIONAL, entries(
                "wrapper.java.additional.10", "-Dten",
                "wrapper.java.additional.2", "-Dtwo",
                "wrapper.java.command", "java",
                "wrapper.java.additional.1", "-Done",
                "wrapper.java.additional.99999999999999999999", "-Dhuge"));

        assertEquals(collected.values(), List.of("-Done", "-Dtwo", "-Dten", "-Dhuge"));
        assertEquals(collected.members().get(0).lastPlaceholder(), "1");
        assertTrue(collected.warnings().isEmpty());
    }

    @Test
    public void stringIndicesSortAfterNumbersAndByTheirNumbers() {
        // The order of the YAJSW reference's own example: 1, 5, ab5, ab12.
        KeyCollector.Collected collected = collector.collect(ConfigKeys.JAVA_CLASSPATH, entries(
                "wrapper.java.classpath.ab12", "e.jar",
                "wrapper.java.classpath.5", "c.jar",
                "wrapper.java.classpath.ab5", "d.jar",
                "wrapper.java.classpath.1", "a.jar",
                "wrapper.java.classpath.lib", "lib/*/*.jar"));

        assertEquals(collected.values(), List.of("a.jar", "c.jar", "d.jar", "e.jar", "lib/*/*.jar"));
        assertTrue(collected.warnings().isEmpty());
    }

    @Test
    public void dottedIndicesSortSegmentBySegment() {
        KeyCollector.Collected collected = collector.collect(ConfigKeys.JAVA_ADDITIONAL, entries(
                "wrapper.java.additional.3", "three",
                "wrapper.java.additional.2.10", "two.ten",
                "wrapper.java.additional.2.2", "two.two",
                "wrapper.java.additional.2", "two"));

        assertEquals(collected.values(), List.of("two", "two.two", "two.ten", "three"));
    }

    @Test
    public void stringIndexWrittenTwiceIsWarnedAbout() {
        KeyCollector.Collected collected = collector.collect(ConfigKeys.JAVA_ADDITIONAL, entries(
                "wrapper.java.additional.opt7", "a",
                "wrapper.java.additional.opt07", "b"));

        assertEquals(collected.values(), List.of("a", "b"));
        assertEquals(collected.warnings().size(), 1);
        assertTrue(collected.warnings().get(0).message().startsWith("wrapper.java.additional.opt07 has the same index"
                + " as wrapper.java.additional.opt7"), collected.warnings().toString());
    }

    @Test
    public void indexWrittenTwiceKeepsBothInInputOrderWithWarning() {
        KeyCollector.Collected collected = collector.collect(ConfigKeys.JAVA_CLASSPATH, entries(
                "wrapper.java.classpath.07", "b.jar",
                "wrapper.java.classpath.7", "a.jar",
                "wrapper.java.classpath.3", "c.jar"));

        assertEquals(collected.values(), List.of("c.jar", "b.jar", "a.jar"));
        assertEquals(collected.warnings().size(), 1);
        ConfigWarning warning = collected.warnings().get(0);
        assertEquals(warning.location(), new ConfigLocation("test.conf", 2));
        assertEquals(warning.message(), "wrapper.java.classpath.7 has the same index as wrapper.java.classpath.07"
                + " (test.conf:1); only one was probably meant");
    }

    @Test
    public void everyRepeatOfAnIndexRefersToTheFirstOne() {
        KeyCollector.Collected collected = collector.collect(ConfigKeys.JAVA_ADDITIONAL, entries(
                "wrapper.java.additional.1", "a",
                "wrapper.java.additional.01", "b",
                "wrapper.java.additional.001", "c"));

        assertEquals(collected.values(), List.of("a", "b", "c"));
        assertEquals(collected.warnings().size(), 2);
        for (ConfigWarning warning : collected.warnings()) {
            assertTrue(warning.message().contains("same index as wrapper.java.additional.1 (test.conf:1)"),
                    warning.message());
        }
    }

    @Test
    public void exitCodesWrittenTwiceAreWarnedAbout() {
        KeyCollector.Collected collected = collector.collect(ConfigKeys.ON_EXIT, entries(
                "wrapper.on_exit.07", "RESTART",
                "wrapper.on_exit.7", "SHUTDOWN",
                "wrapper.on_exit.-0", "STOP",
                "wrapper.on_exit.00", "STOP"));

        assertEquals(collected.members().stream().map(KeyCollector.Member::key).toList(),
                List.of("wrapper.on_exit.-0", "wrapper.on_exit.00", "wrapper.on_exit.07", "wrapper.on_exit.7"));
        assertEquals(collected.warnings().size(), 2);
        assertEquals(collected.members().get(3).numbers(), List.of(BigInteger.valueOf(7)));
    }

    @Test
    public void negativeExitCodesSortByValueNotText() {
        KeyCollector.Collected collected = collector.collect(ConfigKeys.ON_EXIT, entries(
                "wrapper.on_exit.-1", "STOP",
                "wrapper.on_exit.-10", "STOP",
                "wrapper.on_exit.2", "STOP",
                "wrapper.on_exit.-2", "STOP"));

        assertEquals(collected.members().stream().map(KeyCollector.Member::lastPlaceholder).toList(),
                List.of("-10", "-2", "-1", "2"));
    }

    @Test
    public void freeFamilyNeverWarnsAboutLookAlikeNumbers() {
        KeyCollector.Collected collected = collector.collect(ConfigKeys.APP_ENV, entries(
                "wrapper.app.env.7", "a",
                "wrapper.app.env.07", "b"));

        assertEquals(collected.values(), List.of("a", "b"));
        assertTrue(collected.warnings().isEmpty());
        assertTrue(collected.members().get(0).numbers().isEmpty());
    }

    @Test
    public void indexFamiliesHaveNoExitCodeNumbers() {
        KeyCollector.Collected collected = collector.collect(ConfigKeys.JAVA_ADDITIONAL,
                entries("wrapper.java.additional.7", "a"));

        assertTrue(collected.members().get(0).numbers().isEmpty());
    }

    @Test
    public void mixedTemplateOrdersByNumbersAndKeepsInputOrderOtherwise() {
        KeySpec mixed = KeySpec.nyajsw("wrapper.x.<name>.<n>", KeyType.STRING, KeyCategory.LIFECYCLE)
                .status(KeyStatus.SUPPORTED).description("X").build();
        KeyCollector custom = new KeyCollector(new KeyRegistry(List.of(mixed)));

        KeyCollector.Collected collected = custom.collect(mixed, entries(
                "wrapper.x.b.2", "b2",
                "wrapper.x.a.2", "a2",
                "wrapper.x.a.1", "a1",
                "wrapper.x.b.02", "b02"));

        assertEquals(collected.values(), List.of("a1", "b2", "a2", "b02"));
        assertEquals(collected.warnings().size(), 1);
        assertTrue(collected.warnings().get(0).message().startsWith("wrapper.x.b.02 has the same index as wrapper.x.b.2"),
                collected.warnings().toString());
    }

    @Test
    public void dynamicKeysKeepInputOrder() {
        KeyCollector.Collected collected = collector.collect(ConfigKeys.APP_ENV, entries(
                "wrapper.app.env.PATH", "p",
                "wrapper.app.env.JAVA_OPTS", "j",
                "wrapper.app.env.A", "a"));

        assertEquals(collected.members().stream().map(KeyCollector.Member::lastPlaceholder).toList(),
                List.of("PATH", "JAVA_OPTS", "A"));
    }

    @Test
    public void exitCodesSortNumericallyAndSkipKeysWithTheirOwnDeclaration() {
        KeyCollector.Collected collected = collector.collect(ConfigKeys.ON_EXIT, entries(
                "wrapper.on_exit.12", "SHUTDOWN",
                "wrapper.on_exit.0", "RESTART",
                "wrapper.on_exit.default", "STOP",
                "wrapper.on_exit.-1", "STOP",
                "wrapper.on_exit.3", "RESTART"));

        assertEquals(collected.members().stream().map(KeyCollector.Member::key).toList(),
                List.of("wrapper.on_exit.-1", "wrapper.on_exit.3", "wrapper.on_exit.12"));
        assertEquals(collector.collect(ConfigKeys.ON_EXIT_0, entries("wrapper.on_exit.0", "RESTART")).values(),
                List.of("RESTART"));
    }

    @Test
    public void suffixAfterIndexIsOwnFamily() {
        List<ConfigEntry> entries = entries(
                "wrapper.filter.script.2", "s2",
                "wrapper.filter.script.2.args", "a2",
                "wrapper.filter.script.1.args", "a1");

        assertEquals(collector.collect(ConfigKeys.FILTER_SCRIPT_ARGS, entries).values(), List.of("a1", "a2"));
        assertEquals(collector.collect(ConfigKeys.FILTER_SCRIPT, entries).values(), List.of("s2"));
    }

    @Test
    public void nothingToCollectGivesEmptyResult() {
        KeyCollector.Collected collected = collector.collect(ConfigKeys.NTSERVICE_DEPENDENCY, List.of());

        assertTrue(collected.members().isEmpty());
        assertTrue(collected.warnings().isEmpty());
    }

    @Test
    public void exactKeyCollectsAtMostItself() {
        KeyCollector.Collected collected = collector.collect(ConfigKeys.JAVA_COMMAND,
                entries("wrapper.java.command", "java", "wrapper.java.command.javaw", "true"));

        assertEquals(collected.values(), List.of("java"));
        expectThrows(IllegalStateException.class, () -> collected.members().get(0).lastPlaceholder());
    }

    @Test
    public void rejectsUndeclaredSpecAndDuplicateKeys() {
        KeySpec foreign = KeySpec.yajsw("wrapper.java.additional.<n>", KeyType.STRING, KeyCategory.JAVA)
                .status(KeyStatus.SUPPORTED).description("X").build();

        expectThrows(IllegalArgumentException.class, () -> collector.collect(foreign, List.of()));
        expectThrows(IllegalArgumentException.class, () -> collector.collect(ConfigKeys.JAVA_ADDITIONAL,
                entries("wrapper.java.additional.1", "a", "wrapper.java.additional.1", "b")));
    }

    @Test
    public void resultsAreUnmodifiable() {
        KeyCollector.Collected collected = collector.collect(ConfigKeys.JAVA_ADDITIONAL,
                entries("wrapper.java.additional.1", "a"));

        expectThrows(UnsupportedOperationException.class, () -> collected.members().clear());
        expectThrows(UnsupportedOperationException.class, () -> collected.members().get(0).placeholders().clear());
    }

    private static List<ConfigEntry> entries(String... keysAndValues) {
        List<ConfigEntry> entries = new ArrayList<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            entries.add(new ConfigEntry(keysAndValues[i], keysAndValues[i + 1], new ConfigLocation("test.conf", i / 2 + 1)));
        }
        return entries;
    }
}
