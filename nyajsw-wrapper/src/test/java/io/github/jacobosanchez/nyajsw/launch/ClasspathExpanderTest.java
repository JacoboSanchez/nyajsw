package io.github.jacobosanchez.nyajsw.launch;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import io.github.jacobosanchez.nyajsw.config.ConfigKeys;
import io.github.jacobosanchez.nyajsw.config.ConfigLoader;
import io.github.jacobosanchez.nyajsw.config.SecretMasker;
import io.github.jacobosanchez.nyajsw.config.WrapperConfig;
import io.github.jacobosanchez.nyajsw.platform.HostOs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import org.testng.annotations.AfterMethod;
import org.testng.SkipException;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class ClasspathExpanderTest {

    private Path work;
    private SecretMasker secrets;

    @BeforeMethod
    public void setUp() throws IOException {
        work = Files.createTempDirectory("nyajsw-classpath");
        for (String file : List.of("lib/b.jar", "lib/a.jar", "lib/notes.txt", "lib/ext/x.jar", "lib/ext/deep/y.jar",
                "plugins/one/p1.jar", "plugins/two/p2.jar", "conf/app.properties")) {
            Path path = work.resolve(file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "x");
        }
        Path conf = Files.writeString(work.resolve("wrapper.conf"),
                "wrapper.java.app.mainclass=Main\nwrapper.ntservice.password=S3cr3t\n", StandardCharsets.UTF_8);
        secrets = WrapperConfig.from(new ConfigLoader(Map.of(), Map.of(), false, ConfigKeys.registry()).load(conf))
                .secrets();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws IOException {
        for (Path locked : List.of(work.resolve("lib/locked"))) {
            if (Files.exists(locked) && !HostOs.isWindows()) {
                Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
            }
        }
        try (var paths = Files.walk(work)) {
            for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.delete(path);
            }
        }
    }

    private ClasspathExpander.Expanded expand(String... entries) {
        return new ClasspathExpander(false).expand(List.of(entries), work, secrets);
    }

    private String at(String relative) {
        return work.resolve(relative).normalize().toString();
    }

    @Test
    public void plainEntriesResolveAgainstTheWorkingDirectory() {
        ClasspathExpander.Expanded expanded = expand("conf", "lib/a.jar");

        assertEquals(expanded.entries(), List.of(at("conf"), at("lib/a.jar")));
        assertTrue(expanded.warnings().isEmpty(), expanded.warnings().toString());
    }

    @Test
    public void starMatchesFilesOfOneDirectoryInPathOrder() {
        assertEquals(expand("lib/*").entries(), List.of(at("lib/a.jar"), at("lib/b.jar"), at("lib/notes.txt")));
        assertEquals(expand("lib/*.jar").entries(), List.of(at("lib/a.jar"), at("lib/b.jar")));
        assertEquals(expand("lib/?.jar").entries(), List.of(at("lib/a.jar"), at("lib/b.jar")));
    }

    @Test
    public void wildcardDirectoriesMatchOneLevel() {
        assertEquals(expand("plugins/*/*.jar").entries(), List.of(at("plugins/one/p1.jar"), at("plugins/two/p2.jar")));
        assertEquals(expand("lib/*/*.jar").entries(), List.of(at("lib/ext/x.jar")));
    }

    @Test
    public void doubleStarMatchesAnyDepth() {
        assertEquals(expand("lib/**/*.jar").entries(),
                List.of(at("lib/a.jar"), at("lib/b.jar"), at("lib/ext/deep/y.jar"), at("lib/ext/x.jar")));
        assertEquals(expand("plugins/**").entries(), List.of(at("plugins/one/p1.jar"), at("plugins/two/p2.jar")));
    }

    @Test
    public void backslashesSeparateSegmentsToo() {
        assertEquals(expand("lib\\*.jar").entries(), List.of(at("lib/a.jar"), at("lib/b.jar")));
    }

    @Test
    public void absoluteEntriesStayAbsolute() {
        String absolute = work.resolve("lib").toString().replace('\\', '/') + "/*.jar";

        assertEquals(expand(absolute).entries(), List.of(at("lib/a.jar"), at("lib/b.jar")));
    }

    @Test
    public void duplicatesKeepTheirFirstPosition() {
        assertEquals(expand("lib/b.jar", "lib/*.jar", "lib/b.jar").entries(), List.of(at("lib/b.jar"), at("lib/a.jar")));
    }

    @Test
    public void missingEntriesAndEmptyWildcardsWarn() {
        ClasspathExpander.Expanded expanded = expand("missing.jar", "nothing/*.jar", "lib/*.zip");

        assertEquals(expanded.entries(), List.of(at("missing.jar")));
        assertEquals(expanded.warnings(), List.of(
                "classpath entry missing.jar does not exist",
                "classpath entry nothing/*.jar matches no file",
                "classpath entry lib/*.zip matches no file"));
    }

    @Test
    public void windowsRulesMatchNamesIgnoringCase() {
        // Only the names after the base directory are matched by nyajsw; the base itself is the file system's.
        List<String> entries = new ClasspathExpander(true).expand(List.of("lib/*.JAR"), work, secrets).entries();

        assertEquals(entries, List.of(at("lib/a.jar"), at("lib/b.jar")));
        assertTrue(new ClasspathExpander(false).expand(List.of("lib/*.JAR"), work, secrets).entries().isEmpty());
    }

    @Test
    public void windowsRulesMergeEntriesThatDifferOnlyInCase() {
        List<String> entries = new ClasspathExpander(true).expand(List.of("lib/A.jar", "lib/*.jar"), work, secrets)
                .entries();

        assertEquals(entries, List.of(at("lib/A.jar"), at("lib/b.jar")));
    }

    @Test
    public void driveRelativeEntriesAreSkippedUnderWindowsRules() {
        ClasspathExpander.Expanded expanded = new ClasspathExpander(true).expand(List.of("C:lib\\*.jar", "D:x.jar"),
                work, secrets);

        assertTrue(expanded.entries().isEmpty(), expanded.entries().toString());
        assertEquals(expanded.warnings().size(), 2, expanded.warnings().toString());
        assertTrue(expanded.warnings().get(0).contains("current directory of drive C:"), expanded.warnings().toString());
    }

    @Test
    public void emptyAndDotSegmentsAfterAWildcardAreIgnoredAndDotDotIsRefused() {
        assertEquals(expand("lib/./*.jar", "lib/*//").entries(),
                List.of(at("lib/a.jar"), at("lib/b.jar"), at("lib/notes.txt")));
        ClasspathExpander.Expanded upwards = expand("lib/*/../a.jar");
        assertTrue(upwards.entries().isEmpty());
        assertTrue(upwards.warnings().get(0).contains("'..' after a wildcard"), upwards.warnings().toString());
        assertEquals(expand("lib/../lib/*.jar").entries(), List.of(at("lib/a.jar"), at("lib/b.jar")));
    }

    @Test
    public void secretsAreMaskedInWarnings() {
        ClasspathExpander.Expanded expanded = expand("S3cr3t/*.jar", "S3cr3t.jar");

        assertEquals(expanded.warnings(), List.of("classpath entry ****/*.jar matches no file",
                "classpath entry ****.jar does not exist"));
    }

    @Test
    public void unreadableDirectoriesAreSkippedWithAWarning() throws IOException {
        if (HostOs.isWindows() || "root".equals(System.getProperty("user.name"))) {
            throw new SkipException("needs POSIX permissions and a user they apply to");
        }
        Path locked = Files.createDirectories(work.resolve("lib/locked"));
        Files.writeString(locked.resolve("z.jar"), "x");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));

        ClasspathExpander.Expanded expanded = expand("lib/**/*.jar");

        assertTrue(expanded.entries().contains(at("lib/a.jar")), expanded.entries().toString());
        assertTrue(expanded.warnings().stream().anyMatch(w -> w.contains("cannot be read")),
                expanded.warnings().toString());
    }

    @Test
    public void linkedDirectoriesAreFollowed() throws IOException {
        try {
            Files.createSymbolicLink(work.resolve("plugins/linked"), work.resolve("lib/ext"));
        } catch (UnsupportedOperationException | FileSystemException e) {
            throw new SkipException("symbolic links need a privilege here: " + e.getMessage());
        }

        assertEquals(expand("plugins/*/*.jar").entries(), List.of(at("plugins/linked/x.jar"),
                at("plugins/one/p1.jar"), at("plugins/two/p2.jar")));
    }

    @Test
    public void regexQuotesLiteralCharacters() {
        assertEquals(ClasspathExpander.regex(List.of("a.b+c", "*.jar")), "\\Qa.b+c\\E/[^/]*\\Q.jar\\E");
        assertEquals(ClasspathExpander.regex(List.of("**", "x?.jar")), "(?:[^/]+/)*\\Qx\\E[^/]\\Q.jar\\E");
    }
}
