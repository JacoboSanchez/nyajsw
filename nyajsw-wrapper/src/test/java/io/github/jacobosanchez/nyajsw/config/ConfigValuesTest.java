package io.github.jacobosanchez.nyajsw.config;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.testng.annotations.Test;

/** The rules typed views rely on, with keys that take effect (SUPPORTED) as later phases will have them. */
public class ConfigValuesTest {

    private static final KeySpec SUPPORTED_PATH = KeySpec.nyajsw("wrapper.test.dir", KeyType.PATH, KeyCategory.JAVA)
            .status(KeyStatus.SUPPORTED).description("X").build();
    private static final KeySpec PLANNED_PATH = KeySpec.nyajsw("wrapper.test.other", KeyType.PATH, KeyCategory.JAVA)
            .status(KeyStatus.PLANNED, "P2").description("X").build();
    private static final KeyRegistry REGISTRY = new KeyRegistry(List.of(SUPPORTED_PATH, PLANNED_PATH));
    private static final Path FILE = Path.of("conf", "wrapper.conf").toAbsolutePath();

    @Test
    public void problemIsAnErrorForAKeyThatTakesEffectAndAWarningOtherwise() {
        ConfigValues values = values("wrapper.test.dir", "a\0b", "wrapper.test.other", "c\0d");

        assertEquals(values.path(SUPPORTED_PATH), Optional.empty());
        assertEquals(values.path(PLANNED_PATH), Optional.empty());
        assertEquals(values.errors().size(), 1);
        assertTrue(values.errors().get(0).getMessage().contains("invalid path a\\u0000b for wrapper.test.dir"),
                values.errors().get(0).getMessage());
        assertEquals(values.warnings().size(), 1);
    }

    @Test
    public void warnNeverStopsTheWrapper() {
        ConfigValues values = values();

        values.warn(ConfigLocation.of("x"), "odd");

        assertTrue(values.errors().isEmpty());
        assertEquals(values.warnings(), List.of(new ConfigWarning(ConfigLocation.of("x"), "odd")));
    }

    @Test
    public void pathsAreAbsoluteAndNormalisedAgainstTheGivenBase() {
        String absolute = FILE.getRoot().resolve("abs").toString();
        ConfigValues values = values("wrapper.test.dir", "x/../y", "wrapper.test.other", absolute);

        assertEquals(values.directory(), FILE.getParent());
        assertEquals(values.path(SUPPORTED_PATH), Optional.of(FILE.getParent().resolve("y")));
        assertEquals(values.path(SUPPORTED_PATH, Path.of("base").toAbsolutePath()),
                Optional.of(Path.of("base").toAbsolutePath().resolve("y")));
        assertEquals(values.path(PLANNED_PATH), Optional.of(FILE.getRoot().resolve("abs")));
    }

    @Test
    public void textTreatsEmptyAsNotSet() {
        KeySpec text = KeySpec.nyajsw("wrapper.test.name", KeyType.STRING, KeyCategory.JAVA)
                .status(KeyStatus.SUPPORTED).description("X").build();
        ConfigValues values = new ConfigValues(config("wrapper.test.name", ""), new KeyRegistry(List.of(text)));

        assertEquals(values.string(text), Optional.of(""));
        assertEquals(values.text(text), Optional.empty());
    }

    private static ConfigValues values(String... keysAndValues) {
        return new ConfigValues(config(keysAndValues), REGISTRY);
    }

    private static LoadedConfig config(String... keysAndValues) {
        List<ConfigEntry> entries = new ArrayList<>();
        Map<String, ConfigLayer> layers = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            ConfigLocation location = new ConfigLocation("w.conf", i / 2 + 1);
            entries.add(new ConfigEntry(keysAndValues[i], keysAndValues[i + 1], location));
            layers.put(keysAndValues[i], ConfigLayer.FILE);
        }
        return new LoadedConfig(FILE, entries, layers, List.of());
    }
}
