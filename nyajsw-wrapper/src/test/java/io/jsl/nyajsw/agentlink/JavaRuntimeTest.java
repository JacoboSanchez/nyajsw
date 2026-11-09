package io.jsl.nyajsw.agentlink;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import io.jsl.nyajsw.TestConfigs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class JavaRuntimeTest {

    private Path dir;

    @BeforeMethod
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("nyajsw-runtime").toRealPath();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws IOException {
        TestConfigs.delete(dir);
    }

    /** A runtime home with {@code bin/java} and, when {@code release} is given, that release file. */
    private String runtime(String name, String release) throws IOException {
        Path home = Files.createDirectories(dir.resolve(name).resolve("bin")).getParent();
        if (release != null) {
            Files.writeString(home.resolve("release"), release, StandardCharsets.ISO_8859_1);
        }
        return home.resolve("bin").resolve("java").toString();
    }

    @Test
    public void aJlinkImageWithoutTheModuleLacksIt() throws IOException {
        assertTrue(JavaRuntime.lacksInstrumentation(runtime("mini",
                "JAVA_VERSION=\"21.0.13\"\nMODULES=\"java.base java.logging\"\n")));
    }

    @Test
    public void aRuntimeThatListsTheModuleOrNothingKeepsTheAgent() throws IOException {
        assertFalse(JavaRuntime.lacksInstrumentation(runtime("full",
                "MODULES=\"java.base java.instrument java.logging\"\n")));
        assertFalse(JavaRuntime.lacksInstrumentation(runtime("java8", null)), "Java 8 has no release modules");
        assertFalse(JavaRuntime.lacksInstrumentation(runtime("old", "JAVA_VERSION=\"1.8.0_402\"\n")));
        assertFalse(JavaRuntime.lacksInstrumentation("java"), "a bare name is looked up on PATH: unknown");
    }

    @Test
    public void theRuntimeThisBuildRunsOnHasIt() {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");

        assertFalse(JavaRuntime.lacksInstrumentation(java.toString()));
        Optional<List<String>> modules = JavaRuntime.modules(Path.of(System.getProperty("java.home"), "release"));
        modules.ifPresent(list -> assertTrue(list.contains("java.instrument"), list.toString()));
        assertEquals(JavaRuntime.modules(dir.resolve("missing")), Optional.empty());
    }
}
