package io.jsl.nyajsw.config;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;
import static org.testng.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.testng.annotations.Test;

/**
 * Keeps {@code docs/COMPATIBILITY.md} equal to what the key registry generates. Run with
 * {@code -Dnyajsw.docs.update=true} to rewrite the document instead of failing (see
 * {@link CompatibilityDocument#REGENERATE_COMMAND}).
 */
public class CompatibilityDocTest {

    private static final String UPDATE_PROPERTY = "nyajsw.docs.update";

    @Test
    public void committedDocumentMatchesTheRegistry() throws IOException {
        Path document = documentPath();
        String expected = CompatibilityDocument.render(ConfigKeys.registry());

        if (Boolean.getBoolean(UPDATE_PROPERTY)) {
            Files.writeString(document, expected, StandardCharsets.UTF_8);
            return;
        }
        if (!Files.isRegularFile(document)) {
            fail(document + " is missing; generate it with: " + CompatibilityDocument.REGENERATE_COMMAND);
        }
        String actual = Files.readString(document, StandardCharsets.UTF_8).replace("\r\n", "\n");
        if (!actual.equals(expected)) {
            fail(document + " is stale; regenerate it with: " + CompatibilityDocument.REGENERATE_COMMAND);
        }
    }

    @Test
    public void everyKeyAppearsAndSampleRowsAreComplete() {
        String document = CompatibilityDocument.render(ConfigKeys.registry());

        for (KeySpec spec : ConfigKeys.registry().specs()) {
            assertTrue(document.contains("| `" + spec.name() + "` |"), spec.name());
        }
        assertTrue(document.contains("| `wrapper.app.account` | Run the application as another user | text | — |"
                + " **unsupported, fatal when present** (P9) |"), "fatal key row");
        assertTrue(document.contains("one of `AUTO_START`, `DEMAND_START`, `DELAYED_AUTO_START`, `DISABLED`;"
                + " alias `DELAY_START` = `DELAYED_AUTO_START`"), "enumeration with alias");
        assertTrue(document.contains("**planned** (P7)<br>nyajsw extension"), "extension marker");
        assertTrue(document.contains("Secret: never logged or shown."), "secret note");
        assertTrue(document.contains("*Verify:* default"), "verification marker");
    }

    @Test
    public void proseIsEscapedForMarkdownTables() {
        KeySpec odd = KeySpec.nyajsw("wrapper.x.<n>", KeyType.STRING, KeyCategory.LIFECYCLE)
                .status(KeyStatus.SUPPORTED).description("Pipes | and <tags>").notes("a|b").build();

        String document = CompatibilityDocument.render(new KeyRegistry(List.of(odd)));

        assertTrue(document.contains("| `wrapper.x.<n>` | Pipes \\| and &lt;tags&gt; |"), document);
        assertTrue(document.contains("| a\\|b |"), document);
        assertFalse(document.contains("\r"));
    }

    @Test
    public void summaryCountsEveryStatus() {
        String document = CompatibilityDocument.render(ConfigKeys.registry());

        assertTrue(document.contains("| **total** | " + ConfigKeys.registry().specs().size() + " |"), document);
        for (KeyStatus status : KeyStatus.values()) {
            long count = ConfigKeys.registry().specs().stream().filter(spec -> spec.status() == status).count();
            assertTrue(document.contains("| " + status.label() + " | " + count + " |"), status.label());
        }
    }

    @Test
    public void renderingIsDeterministic() {
        assertEquals(CompatibilityDocument.render(ConfigKeys.registry()),
                CompatibilityDocument.render(ConfigKeys.registry()));
    }

    @Test
    public void agentsMdDocumentsTheRegenerateCommand() throws IOException {
        String agents = Files.readString(repositoryRoot().resolve("AGENTS.md"), StandardCharsets.UTF_8);

        assertTrue(agents.contains(CompatibilityDocument.REGENERATE_COMMAND), "AGENTS.md section 3 is out of date");
    }

    @Test
    public void backslashesAreKeptAndEveryRowIsOneLine() {
        KeySpec account = KeySpec.nyajsw("wrapper.x", KeyType.STRING, KeyCategory.SERVICE)
                .status(KeyStatus.SUPPORTED).description("Write DOMAIN\\\\svc").build();

        String document = CompatibilityDocument.render(new KeyRegistry(List.of(account)));

        assertTrue(document.contains("| Write DOMAIN\\\\\\\\svc |"), document);
        assertTrue(document.endsWith("- `wrapper.x`\n") && !document.endsWith("\n\n"), "single final newline");
        expectThrows(IllegalArgumentException.class, () -> KeySpec.nyajsw("wrapper.y", KeyType.STRING,
                KeyCategory.SERVICE).status(KeyStatus.SUPPORTED).description("two\nlines").build());
    }

    @Test
    public void extensionsAreListedTogether() {
        String document = CompatibilityDocument.render(ConfigKeys.registry());
        String extensions = document.substring(document.indexOf("## nyajsw extensions"));

        for (KeySpec spec : ConfigKeys.registry().specs()) {
            assertEquals(extensions.contains("- `" + spec.name() + "`\n"), spec.origin() == KeyOrigin.NYAJSW, spec.name());
        }
    }

    private static Path documentPath() {
        return repositoryRoot().resolve("docs").resolve("COMPATIBILITY.md");
    }

    /**
     * The repository root, found by walking up from the working directory (the module directory
     * under Maven, maybe the root in an IDE) to the directory holding AGENTS.md and the plan, so
     * update mode can never write anywhere else.
     */
    private static Path repositoryRoot() {
        Path start = Path.of(System.getProperty("basedir", System.getProperty("user.dir"))).toAbsolutePath();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("AGENTS.md"))
                    && Files.isRegularFile(dir.resolve("docs").resolve("IMPLEMENTATION_PLAN.md"))) {
                return dir;
            }
        }
        throw new AssertionError("no repository root (AGENTS.md, docs/IMPLEMENTATION_PLAN.md) above " + start);
    }
}
