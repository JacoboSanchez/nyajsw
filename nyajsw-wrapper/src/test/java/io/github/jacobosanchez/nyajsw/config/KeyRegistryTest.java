package io.github.jacobosanchez.nyajsw.config;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.util.List;
import java.util.Optional;

import org.testng.annotations.Test;

public class KeyRegistryTest {

    private static final KeySpec EXACT = spec("wrapper.on_exit.default");
    private static final KeySpec ANY_CODE = spec("wrapper.on_exit.<code>");
    private static final KeySpec SCRIPT = spec("wrapper.filter.script.<n>");
    private static final KeySpec SCRIPT_ARGS = spec("wrapper.filter.script.<n>.args");
    private static final KeySpec SECRET = KeySpec.yajsw("wrapper.app.credential", KeyType.STRING, KeyCategory.JAVA)
            .status(KeyStatus.SUPPORTED).description("X").secret().build();

    @Test
    public void exactNameWinsOverTemplate() {
        KeyRegistry registry = new KeyRegistry(List.of(ANY_CODE, EXACT));

        assertEquals(registry.lookup("wrapper.on_exit.default"), Optional.of(EXACT));
        assertEquals(registry.lookup("wrapper.on_exit.12"), Optional.of(ANY_CODE));
    }

    @Test
    public void moreSpecificTemplateWins() {
        KeySpec anyFilter = spec("wrapper.filter.<setting>");
        KeyRegistry registry = new KeyRegistry(List.of(anyFilter, SCRIPT, SCRIPT_ARGS));

        assertEquals(registry.lookup("wrapper.filter.script.2.args"), Optional.of(SCRIPT_ARGS));
        assertEquals(registry.lookup("wrapper.filter.script.2"), Optional.of(SCRIPT));
        assertEquals(registry.lookup("wrapper.filter.script.x"), Optional.of(anyFilter));
    }

    @Test
    public void unknownKeysAreEmpty() {
        KeyRegistry registry = new KeyRegistry(List.of(EXACT, SCRIPT));

        assertEquals(registry.lookup("wrapper.on_exit"), Optional.empty());
        assertEquals(registry.lookup("wrapper.filter.script.2.args"), Optional.empty());
        assertEquals(registry.lookup("JAVA_HOME"), Optional.empty());
    }

    @Test
    public void specsKeepDeclarationOrderAndAreUnmodifiable() {
        KeyRegistry registry = new KeyRegistry(List.of(SCRIPT_ARGS, EXACT, SCRIPT));

        assertEquals(registry.specs(), List.of(SCRIPT_ARGS, EXACT, SCRIPT));
        expectThrows(UnsupportedOperationException.class, () -> registry.specs().add(ANY_CODE));
    }

    @Test
    public void rejectsDuplicateNames() {
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class,
                () -> new KeyRegistry(List.of(EXACT, spec("wrapper.on_exit.default"))));

        assertTrue(e.getMessage().contains("declared twice: wrapper.on_exit.default"), e.getMessage());
    }

    @Test
    public void rejectsTemplatesThatDifferOnlyInLabels() {
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class,
                () -> new KeyRegistry(List.of(spec("wrapper.x.<a>.y"), spec("wrapper.x.<b>.y"))));

        assertTrue(e.getMessage().contains("equally specific and both match wrapper.x.1.y"), e.getMessage());
    }

    @Test
    public void rejectsEquallySpecificTemplatesThatOverlap() {
        assertOverlapRejected("wrapper.a.<x>.c", "wrapper.a.b.<y>", "wrapper.a.b.c");
        assertOverlapRejected("wrapper.x.<n>.y", "wrapper.x.<code>.y", "wrapper.x.1.y");
    }

    @Test
    public void overlappingTemplatesWithDifferentSpecificityFollowTheRule() {
        // Both match wrapper.readiness.http.timeout; the one with more literal characters wins.
        KeySpec anyTimeout = spec("wrapper.readiness.<type>.timeout");
        KeySpec anyHttp = spec("wrapper.readiness.http.<option>");
        KeyRegistry registry = new KeyRegistry(List.of(anyHttp, anyTimeout));

        assertEquals(registry.lookup("wrapper.readiness.http.timeout"), Optional.of(anyTimeout));
        assertEquals(registry.lookup("wrapper.readiness.http.url"), Optional.of(anyHttp));
    }

    @Test
    public void indexTemplateIsMoreSpecificThanFreeTemplate() {
        KeySpec index = spec("wrapper.x.<n>");
        KeySpec free = spec("wrapper.x.<name>");
        KeyRegistry registry = new KeyRegistry(List.of(free, index));

        assertEquals(registry.lookup("wrapper.x.7"), Optional.of(index));
        assertEquals(registry.lookup("wrapper.x.seven"), Optional.of(free));
        // The label does not matter: any free label ranks below <n>.
        KeySpec otherLabel = spec("wrapper.x.<id>");
        assertEquals(new KeyRegistry(List.of(otherLabel, index)).lookup("wrapper.x.id"), Optional.of(otherLabel));
    }

    @Test
    public void freePlaceholderLabelledLikeASiblingExactKeyIsReportedShadowed() {
        // Documented limitation: the example of <default> is the exact sibling's name.
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class,
                () -> new KeyRegistry(List.of(EXACT, spec("wrapper.on_exit.<default>"))));

        assertTrue(e.getMessage().contains("shadowed"), e.getMessage());
    }

    @Test
    public void secretsByDeclarationAndByPasswordName() {
        KeyRegistry registry = new KeyRegistry(List.of(SECRET, EXACT));

        assertTrue(registry.isSecret("wrapper.app.credential"));
        assertTrue(registry.isSecret("wrapper.unknown.password"));
        assertTrue(registry.isSecret("wrapper.unknown.PASSWORD"));
        assertFalse(registry.isSecret("wrapper.on_exit.default"));
        assertFalse(registry.isSecret("wrapper.unknown.passwords"));
        assertFalse(registry.isSecret("wrapper.unknown"));
    }

    private static void assertOverlapRejected(String first, String second, String key) {
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class,
                () -> new KeyRegistry(List.of(spec(first), spec(second))));

        assertTrue(e.getMessage().contains("equally specific and both match " + key), e.getMessage());
    }

    private static KeySpec spec(String template) {
        return KeySpec.yajsw(template, KeyType.STRING, KeyCategory.LIFECYCLE)
                .status(KeyStatus.SUPPORTED)
                .description("X")
                .build();
    }
}
