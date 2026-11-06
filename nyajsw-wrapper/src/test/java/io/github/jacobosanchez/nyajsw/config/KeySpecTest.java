package io.github.jacobosanchez.nyajsw.config;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.testng.annotations.Test;

public class KeySpecTest {

    @Test
    public void builderFillsEveryComponent() {
        KeySpec spec = KeySpec.nyajsw("wrapper.x.mode", KeyType.ENUM, KeyCategory.LIFECYCLE)
                .status(KeyStatus.PLANNED, "P3")
                .description("Mode")
                .values(List.of("ON", "OFF"))
                .alias("YES", "ON")
                .defaultValue("ON")
                .notes("a note")
                .build();

        assertEquals(spec.name(), "wrapper.x.mode");
        assertEquals(spec.pattern(), KeyPattern.of("wrapper.x.mode"));
        assertEquals(spec.type(), KeyType.ENUM);
        assertEquals(spec.category(), KeyCategory.LIFECYCLE);
        assertEquals(spec.origin(), KeyOrigin.NYAJSW);
        assertEquals(spec.status(), KeyStatus.PLANNED);
        assertEquals(spec.phase(), "P3");
        assertEquals(spec.description(), "Mode");
        assertEquals(spec.values(), List.of("ON", "OFF"));
        assertEquals(spec.aliases(), Map.of("YES", "ON"));
        assertEquals(spec.defaultValue(), KeyDefault.of("ON"));
        assertEquals(spec.notes(), "a note");
        assertEquals(spec.unverified(), Set.of());
        assertFalse(spec.secret());
    }

    @Test
    public void unverifiedPartsAreKeptForYajswKeysOnly() {
        KeySpec spec = base(KeyType.STRING).toVerify(Unverified.NAME, Unverified.DEFAULT).build();

        assertEquals(spec.unverified(), Set.of(Unverified.NAME, Unverified.DEFAULT));
        expectThrows(UnsupportedOperationException.class, () -> spec.unverified().add(Unverified.VALUES));
        assertInvalid(KeySpec.nyajsw("wrapper.x", KeyType.STRING, KeyCategory.PROBES)
                .status(KeyStatus.SUPPORTED).description("X").toVerify(Unverified.NAME), "nothing to verify");
    }

    @Test
    public void aliasesKeepDeclarationOrder() {
        List<String> aliases = List.of("A1", "B1", "C1", "D1", "A2", "B2", "C2", "D2");
        KeySpec.Builder builder = base(KeyType.ENUM).values(List.of("V"));
        aliases.forEach(alias -> builder.alias(alias, "V"));

        assertEquals(List.copyOf(builder.build().aliases().keySet()), aliases);
    }

    @Test
    public void defaultsOfTheBuilder() {
        KeySpec spec = KeySpec.yajsw("wrapper.x", KeyType.STRING, KeyCategory.JAVA)
                .status(KeyStatus.SUPPORTED)
                .description("X")
                .build();

        assertEquals(spec.origin(), KeyOrigin.YAJSW);
        assertEquals(spec.defaultValue(), KeyDefault.NONE);
        assertEquals(spec.phase(), "");
        assertEquals(spec.notes(), "");
        assertTrue(spec.values().isEmpty());
        assertTrue(spec.aliases().isEmpty());
    }

    @Test
    public void derivedDefaultKeepsItsDescription() {
        KeySpec spec = KeySpec.yajsw("wrapper.x", KeyType.PATH, KeyCategory.JAVA)
                .status(KeyStatus.SUPPORTED)
                .description("X")
                .derivedDefault("the configuration directory")
                .build();

        assertEquals(spec.defaultValue(), KeyDefault.derived("the configuration directory"));
    }

    @Test
    public void canonicalValueIgnoresCaseAndResolvesAliases() {
        KeySpec spec = KeySpec.yajsw("wrapper.x", KeyType.ENUM, KeyCategory.SERVICE)
                .status(KeyStatus.SUPPORTED)
                .description("X")
                .values(List.of("AUTO_START", "DELAYED_AUTO_START"))
                .alias("DELAY_START", "DELAYED_AUTO_START")
                .build();

        assertEquals(spec.canonicalValue("AUTO_START"), Optional.of("AUTO_START"));
        assertEquals(spec.canonicalValue("auto_start"), Optional.of("AUTO_START"));
        assertEquals(spec.canonicalValue("delay_start"), Optional.of("DELAYED_AUTO_START"));
        assertEquals(spec.canonicalValue("SOMETIMES"), Optional.empty());
    }

    @Test
    public void matchesDelegatesToThePattern() {
        KeySpec spec = KeySpec.yajsw("wrapper.x.<n>", KeyType.STRING, KeyCategory.JAVA)
                .status(KeyStatus.SUPPORTED)
                .description("X")
                .build();

        assertTrue(spec.matches("wrapper.x.2"));
        assertFalse(spec.matches("wrapper.x.y"));
    }

    @Test
    public void requiresStatusAndDescription() {
        expectThrows(IllegalStateException.class,
                () -> KeySpec.yajsw("wrapper.x", KeyType.STRING, KeyCategory.JAVA).description("X").build());
        expectThrows(IllegalStateException.class,
                () -> KeySpec.yajsw("wrapper.x", KeyType.STRING, KeyCategory.JAVA)
                        .status(KeyStatus.SUPPORTED).build());
        assertInvalid(base(KeyType.STRING).description(" "), "description");
    }

    @Test
    public void plannedNeedsPhaseAndSupportedOrIgnoredHaveNone() {
        assertInvalid(base(KeyType.STRING).status(KeyStatus.PLANNED), "phase");
        assertInvalid(base(KeyType.STRING).status(KeyStatus.SUPPORTED, "P2"), "no phase");
        assertInvalid(base(KeyType.STRING).status(KeyStatus.IGNORED, "P2"), "no phase");
        assertInvalid(base(KeyType.STRING).status(KeyStatus.PLANNED, "soon"), "phase must look like");
        assertInvalid(base(KeyType.STRING).status(KeyStatus.UNSUPPORTED, "P9.1"), "phase must look like");
        // Unsupported keys may name the backlog phase that would implement them, or none.
        base(KeyType.STRING).status(KeyStatus.UNSUPPORTED, "P9").build();
        base(KeyType.STRING).status(KeyStatus.UNSUPPORTED_FATAL).build();
    }

    @Test
    public void unsupportedKeysHaveNoConstantDefault() {
        assertInvalid(base(KeyType.STRING).status(KeyStatus.UNSUPPORTED).defaultValue("x"), "constant default");
        assertInvalid(base(KeyType.STRING).status(KeyStatus.UNSUPPORTED_FATAL).defaultValue("x"), "constant default");
        base(KeyType.STRING).status(KeyStatus.UNSUPPORTED).derivedDefault("documented only").build();
    }

    @Test
    public void passwordKeysMustBeSecretAndSecretsHaveNoConstantDefault() {
        assertInvalid(KeySpec.yajsw("wrapper.x.Password", KeyType.STRING, KeyCategory.JAVA)
                .status(KeyStatus.SUPPORTED).description("X"), "secret");
        assertInvalid(base(KeyType.STRING).secret().defaultValue("changeit"), "secret");
        KeySpec secret = KeySpec.yajsw("wrapper.x.password", KeyType.STRING, KeyCategory.JAVA)
                .status(KeyStatus.SUPPORTED).description("X").secret().build();
        assertTrue(secret.secret());
    }

    @Test
    public void enumerationValuesAndAliasesAreChecked() {
        assertInvalid(base(KeyType.ENUM), "needs its values");
        assertInvalid(base(KeyType.STRING).values(List.of("A")), "only enumeration");
        assertInvalid(base(KeyType.STRING).alias("A", "B"), "only enumeration");
        assertInvalid(base(KeyType.ENUM).values(List.of("A", "a")), "unique");
        assertInvalid(base(KeyType.ENUM).values(List.of("A", " ")), "unique");
        assertInvalid(base(KeyType.ENUM).values(List.of("A")).alias("B", "C"), "undeclared");
        assertInvalid(base(KeyType.ENUM).values(List.of("A", "B")).alias("b", "A"), "repeats");
        assertInvalid(base(KeyType.ENUM).values(List.of("A")).alias(" ", "A"), "non-blank");
    }

    @Test
    public void constantDefaultsMustFitTheType() {
        assertInvalid(base(KeyType.ENUM).values(List.of("A")).defaultValue("B"), "default 'B'");
        assertInvalid(base(KeyType.INTEGER).defaultValue("five"), "default 'five'");
        assertInvalid(base(KeyType.DURATION).defaultValue("1.5"), "default '1.5'");
        assertInvalid(base(KeyType.BOOLEAN).defaultValue("yes"), "default 'yes'");
        assertInvalid(base(KeyType.STRING).defaultValue(""), "default ''");
        base(KeyType.INTEGER).defaultValue("-1").build();
        base(KeyType.BOOLEAN).defaultValue("false").build();
        assertInvalid(base(KeyType.SIZE).defaultValue("lots"), "default 'lots'");
        assertInvalid(base(KeyType.SIZE).defaultValue("10MB"), "default '10MB'");
        base(KeyType.SIZE).defaultValue("10M").build();
        base(KeyType.SIZE).defaultValue("512").build();
    }

    @Test
    public void derivedDefaultNeedsADescription() {
        expectThrows(IllegalArgumentException.class, () -> KeyDefault.derived(" "));
    }

    @Test
    public void collectionsAreUnmodifiable() {
        KeySpec spec = base(KeyType.ENUM).values(List.of("A")).build();

        expectThrows(UnsupportedOperationException.class, () -> spec.values().add("B"));
        expectThrows(UnsupportedOperationException.class, () -> spec.aliases().put("B", "A"));
    }

    private static KeySpec.Builder base(KeyType type) {
        return KeySpec.yajsw("wrapper.x", type, KeyCategory.JAVA).status(KeyStatus.SUPPORTED).description("X");
    }

    private static void assertInvalid(KeySpec.Builder builder, String expectedText) {
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, builder::build);
        assertTrue(e.getMessage().contains(expectedText), e.getMessage());
    }
}
