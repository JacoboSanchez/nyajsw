package io.github.jacobosanchez.nyajsw.config;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class FileConfigSourceTest {

    private Path dir;

    @BeforeMethod
    public void createDir() throws IOException {
        dir = Files.createTempDirectory("nyajsw-config-test");
    }

    @AfterMethod(alwaysRun = true)
    public void deleteDir() throws IOException {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    // ---- fixtures (byte-exact files under src/test/resources/conf/parser) ----

    @Test
    public void readsCrlfFixtureWithCommentsSeparatorsBackslashesAndContinuation() throws Exception {
        Path file = fixture("crlf.conf");

        SourceContent content = new FileConfigSource(file).read();

        assertEquals(content.entries(), List.of(
                entry("wrapper.java.app.mainclass", "com.example.Main", file, 5),
                entry("wrapper.java.additional.1", "-Dkey=a=b", file, 6),
                entry("wrapper.app.parameter.1", "value # not a comment", file, 7),
                entry("wrapper.working.dir", "C:\\app\\", file, 8), // C:\app\\ as written, unescaped
                entry("wrapper.java.classpath.1", "C:\\app\\lib\\*.jar", file, 9),
                entry("wrapper.ntservice.description", "first part second part", file, 10),
                entry("wrapper.ntservice.displayname", "", file, 12)));
        assertTrue(content.warnings().isEmpty(), content.warnings().toString());
    }

    @Test
    public void skipsUtf8ByteOrderMark() throws Exception {
        SourceContent content = new FileConfigSource(fixture("utf8-bom.conf")).read();

        assertEquals(content.entries().size(), 1);
        assertEquals(content.entries().get(0).key(), "wrapper.ntservice.displayname");
        assertEquals(content.entries().get(0).value(), "Café € service");
        assertTrue(content.warnings().isEmpty());
    }

    @Test
    public void fallsBackToWindows1252WithWarningWhenNotUtf8() throws Exception {
        Path file = fixture("windows-1252.conf");

        SourceContent content = new FileConfigSource(file).read();

        assertEquals(content.entries().get(0).value(), "Café € service");
        assertEquals(content.warnings().size(), 1);
        ConfigWarning warning = content.warnings().get(0);
        assertEquals(warning.location(), ConfigLocation.of(file.toString()));
        assertTrue(warning.message().contains("not valid UTF-8"), warning.message());
    }

    @Test
    public void rejectsUtf16File() throws Exception {
        Path file = fixture("utf16le-bom.conf");

        ConfigException e = expectThrows(ConfigException.class, () -> new FileConfigSource(file).read());

        assertEquals(e.location().orElseThrow(), ConfigLocation.of(file.toString()));
        assertTrue(e.getMessage().contains("UTF-16"), e.getMessage());
    }

    @Test
    public void rejectsNulBytesWithoutByteOrderMark() throws IOException {
        Path file = dir.resolve("nul-bytes.conf");
        Files.write(file, "a\u0000=\u00001".getBytes(StandardCharsets.UTF_8));

        ConfigException e = expectThrows(ConfigException.class, () -> new FileConfigSource(file).read());

        assertTrue(e.getMessage().contains("NUL"), e.getMessage());
    }

    @Test
    public void rejectsByteOrderMarkFollowedByInvalidUtf8() throws Exception {
        Path file = fixture("bom-invalid-utf8.conf");

        ConfigException e = expectThrows(ConfigException.class, () -> new FileConfigSource(file).read());

        assertEquals(e.location().orElseThrow(), ConfigLocation.of(file.toString()));
        assertTrue(e.getMessage().contains("byte order mark"), e.getMessage());
    }

    // ---- line syntax ----

    @Test
    public void acceptsLfAndCrLineEndings() throws IOException {
        Path file = write("main.conf", "a=1\nb=2\rc=3\r\nd=4");

        List<ConfigEntry> entries = new FileConfigSource(file).read().entries();

        assertEquals(entries, List.of(entry("a", "1", file, 1), entry("b", "2", file, 2),
                entry("c", "3", file, 3), entry("d", "4", file, 4)));
    }

    @Test
    public void continuationSpansSeveralLinesAndKeepsStartLine() throws IOException {
        Path file = write("main.conf", "\nkey = one \\\n  two \\\n\tthree\nnext=x\n");

        List<ConfigEntry> entries = new FileConfigSource(file).read().entries();

        assertEquals(entries, List.of(entry("key", "one two three", file, 2), entry("next", "x", file, 5)));
    }

    @Test
    public void commentAndBlankLinesInsideContinuationAreSkipped() throws IOException {
        Path file = write("main.conf", "cp=lib/a.jar;\\\n# lib/old.jar;\\\n\n  ! another\nlib/b.jar\nnext=x\n");

        SourceContent content = new FileConfigSource(file).read();

        assertEquals(content.entries(), List.of(entry("cp", "lib/a.jar;lib/b.jar", file, 1),
                entry("next", "x", file, 6)));
        assertTrue(content.warnings().isEmpty(), content.warnings().toString());
    }

    @Test
    public void includeInsideContinuationIsIgnoredWithWarning() throws IOException {
        write("other.conf", "o=1\n");
        Path file = write("main.conf", "k=a\\\n#include other.conf\nb\n");

        SourceContent content = new FileConfigSource(file).read();

        assertEquals(content.entries(), List.of(entry("k", "ab", file, 1)));
        assertEquals(content.warnings().size(), 1);
        assertEquals(content.warnings().get(0).location(), new ConfigLocation(file.toString(), 2));
    }

    @Test
    public void warnsWhenSingleTrailingBackslashSwallowsNextEntry() throws IOException {
        Path file = write("main.conf", "wrapper.working.dir=C:\\app\\\nwrapper.java.command=java\n");

        SourceContent content = new FileConfigSource(file).read();

        assertEquals(content.entries(), List.of(entry("wrapper.working.dir", "C:\\appwrapper.java.command=java",
                file, 1)));
        assertEquals(content.warnings().size(), 1);
        ConfigWarning warning = content.warnings().get(0);
        assertEquals(warning.location(), new ConfigLocation(file.toString(), 2));
        assertFalse(warning.message().contains("C:\\app"), warning.message());
    }

    @Test
    public void ordinaryContinuationLinesDoNotWarn() throws IOException {
        Path file = write("main.conf", "wrapper.java.additional.1=-Da=1 \\\n  -Db=2\n");

        assertTrue(new FileConfigSource(file).read().warnings().isEmpty());
    }

    @Test
    public void trimRemovesControlCharactersSuchAsDosEndOfFile() throws IOException {
        Path file = write("main.conf", "a=1\r\n\u001A");

        SourceContent content = new FileConfigSource(file).read();

        assertEquals(content.entries(), List.of(entry("a", "1", file, 1)));
        assertTrue(content.warnings().isEmpty(), content.warnings().toString());
    }

    @Test
    public void oddTrailingBackslashesContinueEvenOnesDoNot() throws IOException {
        Path file = write("main.conf", "odd=C:\\\\\\\nx\neven=C:\\\\\nnext=y\n");

        List<ConfigEntry> entries = new FileConfigSource(file).read().entries();

        // The pair before the continuing backslash is an escaped backslash, as in YAJSW.
        assertEquals(entries, List.of(entry("odd", "C:\\x", file, 1), entry("even", "C:\\", file, 3),
                entry("next", "y", file, 4)));
    }

    @Test
    public void escapedBackslashesAndCommasAreUndoneAndOtherBackslashesKept() throws IOException {
        Path file = write("main.conf", "locales=-Djava.locale.providers=COMPAT\\,SPI\n"
                + "mixed=C:/Denodo/9\\\\conf\n"
                + "single=C:\\app\\lib\n"
                + "unc=\\\\\\\\server\\\\share\n"
                + "comma=a,b\n"
                + "include = inc\\\\x.conf\n");
        Files.createDirectories(file.getParent().resolve("inc"));
        Files.writeString(file.getParent().resolve("inc").resolve("x.conf"), "included=yes\n");

        List<ConfigEntry> entries = new FileConfigSource(file).read().entries();

        assertEquals(entries.stream().map(ConfigEntry::value).toList(), List.of(
                "-Djava.locale.providers=COMPAT,SPI", "C:/Denodo/9\\conf", "C:\\app\\lib", "\\\\server\\share", "a,b",
                "yes"));
    }

    @Test
    public void unescapeHandlesEdgeCases() {
        assertEquals(FileConfigSource.unescape(""), "");
        assertEquals(FileConfigSource.unescape("\\"), "\\");
        assertEquals(FileConfigSource.unescape("a\\\\\\,b"), "a\\,b");
        assertEquals(FileConfigSource.unescape("\\\\\\"), "\\\\");
        // YAJSW's other character escapes are undone too; control and Unicode escapes stay literal.
        assertEquals(FileConfigSource.unescape("-Dx=\\\"a b\\\" \\#\\=\\:\\!\\'\\ "), "-Dx=\"a b\" #=:!' ");
        assertEquals(FileConfigSource.unescape("C:\\temp\\new\\users\\b\\f\\r"), "C:\\temp\\new\\users\\b\\f\\r");
    }

    @Test
    public void continuationAtEndOfFileKeepsValueWithWarning() throws IOException {
        Path file = write("main.conf", "key=value\\\n# trailing comment\n\n");

        SourceContent content = new FileConfigSource(file).read();

        assertEquals(content.entries(), List.of(entry("key", "value", file, 1)));
        assertEquals(content.warnings().size(), 1);
        assertEquals(content.warnings().get(0).location(), new ConfigLocation(file.toString(), 1));
    }

    @Test
    public void commentLinesDoNotContinue() throws IOException {
        Path file = write("main.conf", "# comment \\\nkey=value\n");

        assertEquals(new FileConfigSource(file).read().entries(), List.of(entry("key", "value", file, 2)));
    }

    @Test
    public void keepsDuplicateKeysInSourceOrder() throws IOException {
        Path file = write("main.conf", "a=1\na=2\n");

        assertEquals(new FileConfigSource(file).read().entries(), List.of(entry("a", "1", file, 1),
                entry("a", "2", file, 2)));
    }

    @Test
    public void warnsWithoutEchoingLineThatHasNoSeparator() throws IOException {
        Path file = write("main.conf", "wrapper.ntservice.password s3cret\nok=1\n");

        SourceContent content = new FileConfigSource(file).read();

        assertEquals(content.entries(), List.of(entry("ok", "1", file, 2)));
        assertEquals(content.warnings().size(), 1);
        ConfigWarning warning = content.warnings().get(0);
        assertEquals(warning.location(), new ConfigLocation(file.toString(), 1));
        assertFalse(warning.toString().contains("s3cret"), warning.toString());
        assertFalse(warning.toString().contains("password"), warning.toString());
    }

    @Test
    public void warnsAboutEmptyKey() throws IOException {
        Path file = write("main.conf", " = value\n");

        SourceContent content = new FileConfigSource(file).read();

        assertTrue(content.entries().isEmpty());
        assertEquals(content.warnings().get(0).location(), new ConfigLocation(file.toString(), 1));
        assertTrue(content.warnings().get(0).message().contains("empty key"));
    }

    @Test
    public void entryToStringOmitsValue() {
        ConfigEntry entry = new ConfigEntry("wrapper.ntservice.password", "s3cret", new ConfigLocation("f", 1));

        assertFalse(entry.toString().contains("s3cret"), entry.toString());
    }

    // ---- includes ----

    @Test
    public void expandsBothIncludeSpellingsInPlaceRelativeToIncludingFile() throws IOException {
        Path hash = write("sub/hash.conf", "h=1\ninclude = ../deeper/assign.conf\n");
        Path assign = write("deeper/assign.conf", "a=2\n");
        Path quoted = write("sub/quoted.conf", "q=3\n");
        Path main = write("main.conf", "first=0\n#include sub/hash.conf\n#include   \"sub/quoted.conf\"\nlast=4\n");

        SourceContent content = new FileConfigSource(main).read();

        assertEquals(content.entries(), List.of(entry("first", "0", main, 1), entry("h", "1", hash, 1),
                entry("a", "2", assign, 1), entry("q", "3", quoted, 1), entry("last", "4", main, 4)));
        assertTrue(content.warnings().isEmpty());
    }

    @Test
    public void includeKeyIsCaseInsensitive() throws IOException {
        Path other = write("other.conf", "o=1\n");
        Path main = write("main.conf", "Include = other.conf\nINCLUDE=other.conf\n");

        assertEquals(new FileConfigSource(main).read().entries(), List.of(entry("o", "1", other, 1),
                entry("o", "1", other, 1)));
    }

    @Test
    public void includeRequiredIsAnInclude() throws IOException {
        Path other = write("other.conf", "o=1\n");
        Path main = write("main.conf", "#include.required other.conf\n");

        assertEquals(new FileConfigSource(main).read().entries(), List.of(entry("o", "1", other, 1)));
    }

    @Test
    public void includeRequiredOfMissingFileIsAnError() throws IOException {
        Path main = write("main.conf", "#include.required missing.conf\n");

        expectThrows(ConfigException.class, () -> new FileConfigSource(main).read());
    }

    @Test
    public void otherHashIncludeDirectivesAreIgnoredWithWarning() throws IOException {
        Path main = write("main.conf", "#include.debug\nk=v\n");

        SourceContent content = new FileConfigSource(main).read();

        assertEquals(content.entries(), List.of(entry("k", "v", main, 2)));
        assertEquals(content.warnings().size(), 1);
        assertTrue(content.warnings().get(0).message().contains("#include.debug"));
    }

    // ---- declared encoding (wrapper.conf.encoding) ----

    @Test
    public void declaredEncodingDecodesTheFileAndItsIncludesWithoutFallbackWarning() throws IOException {
        Path extra = dir.resolve("extra.conf");
        Files.write(extra, "e=\u00e9t\u00e9\n".getBytes(StandardCharsets.ISO_8859_1));
        Path main = dir.resolve("main.conf");
        Files.write(main, "k=caf\u00e9\ninclude = extra.conf\n".getBytes(StandardCharsets.ISO_8859_1));

        SourceContent content = new FileConfigSource(main, interpolator(Map.of(), Map.of()),
                FileConfigSource.Layers.NONE, declared(StandardCharsets.ISO_8859_1)).read();

        assertEquals(content.entries().stream().map(ConfigEntry::value).toList(), List.of("caf\u00e9", "\u00e9t\u00e9"));
        assertTrue(content.warnings().isEmpty(), content.warnings().toString());
    }

    @Test
    public void declaredEncodingIsStrict() throws IOException {
        Path main = dir.resolve("main.conf");
        Files.write(main, "k=caf\u00e9\n".getBytes(StandardCharsets.ISO_8859_1));

        ConfigException e = expectThrows(ConfigException.class, () -> new FileConfigSource(main,
                interpolator(Map.of(), Map.of()), FileConfigSource.Layers.NONE, declared(StandardCharsets.UTF_8))
                .read());

        assertEquals(e.getMessage(), main + ":1: byte that is not valid UTF-8, the encoding set by test.conf:7");
    }

    @Test
    public void strictDecodingNamesTheLineOfTheFirstBadByte() throws IOException {
        Path main = dir.resolve("main.conf");
        Files.write(main, "a=1\nb=2\n# Gr\u00fc\u00dfe\nc=3\n".getBytes(StandardCharsets.ISO_8859_1));

        ConfigException e = expectThrows(ConfigException.class, () -> new FileConfigSource(main,
                interpolator(Map.of(), Map.of()), FileConfigSource.Layers.NONE, declared(StandardCharsets.UTF_8))
                .read());

        assertEquals(e.location().orElseThrow(), new ConfigLocation(main.toString(), 3));
    }

    @Test
    public void declaredUtf16SkipsItsByteOrderMark() throws IOException {
        Path main = dir.resolve("main.conf");
        Files.write(main, "\uFEFFk=v\n".getBytes(StandardCharsets.UTF_16LE));

        SourceContent content = new FileConfigSource(main, interpolator(Map.of(), Map.of()),
                FileConfigSource.Layers.NONE, declared(StandardCharsets.UTF_16LE)).read();

        assertEquals(content.entries(), List.of(entry("k", "v", main, 1)));
    }

    @Test
    public void utf8ByteOrderMarkUnderAnotherDeclaredEncodingIsAnError() throws IOException {
        Path main = dir.resolve("main.conf");
        Files.write(main, "\uFEFFk=v\n".getBytes(StandardCharsets.UTF_8));

        ConfigException e = expectThrows(ConfigException.class, () -> new FileConfigSource(main,
                interpolator(Map.of(), Map.of()), FileConfigSource.Layers.NONE, declared(StandardCharsets.ISO_8859_1))
                .read());

        assertTrue(e.getMessage().contains("UTF-8 byte order mark"), e.getMessage());
    }

    @Test
    public void utf16RejectionPointsAtTheSystemProperty() throws IOException {
        Path main = dir.resolve("main.conf");
        Files.write(main, "\uFEFFk=v\n".getBytes(StandardCharsets.UTF_16LE));

        ConfigException e = expectThrows(ConfigException.class,
                () -> new FileConfigSource(main, interpolator(Map.of(), Map.of())).read());

        assertTrue(e.getMessage().contains("-Dwrapper.conf.encoding=UTF-16"), e.getMessage());
    }

    @Test
    public void declaredUtf16IsAccepted() throws IOException {
        Path main = dir.resolve("main.conf");
        Files.write(main, "k=v\n".getBytes(StandardCharsets.UTF_16LE));

        SourceContent content = new FileConfigSource(main, interpolator(Map.of(), Map.of()),
                FileConfigSource.Layers.NONE, declared(StandardCharsets.UTF_16LE)).read();

        assertEquals(content.entries(), List.of(entry("k", "v", main, 1)));
    }

    @Test
    public void declaredUtf8SkipsTheByteOrderMark() throws IOException {
        Path main = dir.resolve("main.conf");
        Files.write(main, ("\uFEFFk=v\n").getBytes(StandardCharsets.UTF_8));

        SourceContent content = new FileConfigSource(main, interpolator(Map.of(), Map.of()),
                FileConfigSource.Layers.NONE, declared(StandardCharsets.UTF_8)).read();

        assertEquals(content.entries(), List.of(entry("k", "v", main, 1)));
    }

    // ---- variables in include paths ----

    @Test
    public void includePathResolvesKeysDefinedEarlierRecursively() throws IOException {
        Path extra = write("conf/extra.conf", "e=1\n");
        Path main = write("main.conf", "app_home=" + slashes(dir) + "\nconf_dir=${app_home}/conf\n"
                + "include = ${conf_dir}/extra.conf\n");

        SourceContent content = new FileConfigSource(main, interpolator(Map.of(), Map.of())).read();

        assertEquals(content.entries().get(2), entry("e", "1", extra, 1));
        assertTrue(content.warnings().isEmpty(), content.warnings().toString());
    }

    @Test
    public void includePathResolvesSystemPropertiesAndEnvironment() throws IOException {
        Path extra = write("conf/extra.conf", "e=1\n");
        Path main = write("main.conf", "#include.required ${sys:base}/${env:SUB}/extra.conf\n");

        SourceContent content = new FileConfigSource(main,
                interpolator(Map.of("base", slashes(dir)), Map.of("SUB", "conf"))).read();

        assertEquals(content.entries(), List.of(entry("e", "1", extra, 1)));
    }

    @Test
    public void includePathUsesTheLastDefinitionReadBeforeTheIncludeLine() throws IOException {
        Path second = write("b/x.conf", "from=b\n");
        write("a/x.conf", "from=a\n");
        write("c/x.conf", "from=c\n");
        Path main = write("main.conf", "sub=a\nsub=b\ninclude = ${sub}/x.conf\nsub=c\n");

        SourceContent content = new FileConfigSource(main, interpolator(Map.of(), Map.of())).read();

        assertEquals(content.entries().get(2), entry("from", "b", second, 1));
    }

    @Test
    public void includedFileSeesKeysOfTheIncludingFile() throws IOException {
        Path leaf = write("conf/leaf.conf", "l=1\n");
        write("conf/middle.conf", "include = ${root}/conf/leaf.conf\n");
        Path main = write("main.conf", "root=" + slashes(dir) + "\ninclude = conf/middle.conf\n");

        SourceContent content = new FileConfigSource(main, interpolator(Map.of(), Map.of())).read();

        assertEquals(content.entries().get(1), entry("l", "1", leaf, 1));
    }

    @Test
    public void includePathPrefersTheLayerAboveThenTheFileThenTheLayerBelow() throws IOException {
        write("above/x.conf", "from=above\n");
        write("file/y.conf", "from=file\n");
        write("below/z.conf", "from=below\n");
        Path main = write("main.conf", "over=file\ninclude = ${over}/x.conf\n"
                + "mid=file\ninclude = ${mid}/y.conf\ninclude = ${low}/z.conf\n");
        ConfigLocation where = ConfigLocation.of("test");
        FileConfigSource.Layers layers = new FileConfigSource.Layers(
                Map.of("over", new ConfigEntry("over", "above", where)),
                Map.of("mid", new ConfigEntry("mid", "below", where), "low", new ConfigEntry("low", "below", where)));

        SourceContent content = new FileConfigSource(main, interpolator(Map.of(), Map.of()), layers).read();

        assertEquals(content.entries().stream().filter(e -> e.key().equals("from")).map(ConfigEntry::value).toList(),
                List.of("above", "file", "below"));
    }

    @Test
    public void unresolvedVariableInRequiredIncludeIsAnError() throws IOException {
        Path main = write("main.conf", "include = ${wrapper_home}/conf/extra.conf\nwrapper_home=late\n");

        ConfigException e = expectThrows(ConfigException.class,
                () -> new FileConfigSource(main, interpolator(Map.of(), Map.of())).read());

        assertEquals(e.location().orElseThrow(), new ConfigLocation(main.toString(), 1));
        assertTrue(e.getMessage().contains("unresolved variable in include path ${wrapper_home}/conf/extra.conf"),
                e.getMessage());
    }

    @Test
    public void variableLeftUnresolvedInAReferencedKeyMakesTheIncludeFail() throws IOException {
        Path main = write("main.conf", "base=${missing}\ninclude = ${base}/x.conf\n");

        ConfigException e = expectThrows(ConfigException.class,
                () -> new FileConfigSource(main, interpolator(Map.of(), Map.of())).read());

        assertTrue(e.getMessage().contains("unresolved variable in include path ${base}/x.conf"), e.getMessage());
    }

    @Test
    public void optionalIncludeWithGroovyOrCycleInItsPathIsSkippedWithWarning() throws IOException {
        Path main = write("main.conf", "a=${a}\n#include ${util.f('x')}\n#include ${a}/x.conf\nk=v\n");

        SourceContent content = new FileConfigSource(main, interpolator(Map.of(), Map.of())).read();

        assertEquals(content.entries().get(1), entry("k", "v", main, 4));
        assertEquals(content.warnings().stream().map(w -> w.location().line()).toList(), List.of(2, 3));
        assertTrue(content.warnings().get(0).message().startsWith("optional include skipped, Groovy"),
                content.warnings().toString());
        assertTrue(content.warnings().get(1).message().contains("cycle"), content.warnings().toString());
    }

    @Test
    public void includePathThatReadsASecretIsRefusedWithoutShowingIt() throws IOException {
        write("TopSecret123.conf", "leak=1\n");
        Path optional = write("optional.conf", "wrapper.ntservice.password=TopSecret123\n"
                + "#include ${wrapper.ntservice.password}.conf\nk=v\n");

        SourceContent content = new FileConfigSource(optional, interpolator(Map.of(), Map.of())).read();

        assertEquals(content.entries().stream().map(ConfigEntry::key).toList(),
                List.of("wrapper.ntservice.password", "k"));
        assertEquals(content.warnings().size(), 1);
        assertEquals(content.warnings().get(0).message(),
                "optional include skipped, include path refers to a secret key, which is not allowed");

        Path required = write("required.conf", "pw_copy=${wrapper.app.password}\nwrapper.app.password=TopSecret123\n"
                + "include = ${pw_copy}.conf\n");
        ConfigException e = expectThrows(ConfigException.class,
                () -> new FileConfigSource(required, interpolator(Map.of(), Map.of())).read());
        assertFalse(e.getMessage().contains("TopSecret123"), e.getMessage());
        assertTrue(e.getMessage().contains("refers to a secret key"), e.getMessage());
    }

    @Test
    public void groovyInIncludePathIsAnError() throws IOException {
        Path main = write("main.conf", "include = ${util.inquireCLI('path')}\n");

        ConfigException e = expectThrows(ConfigException.class,
                () -> new FileConfigSource(main, interpolator(Map.of(), Map.of())).read());

        assertTrue(e.getMessage().contains("Groovy"), e.getMessage());
    }

    @Test
    public void keystoreReferenceInIncludePathIsAnError() throws IOException {
        Path main = write("main.conf", "include = ${keystore confdir}/x.conf\n");

        ConfigException e = expectThrows(ConfigException.class,
                () -> new FileConfigSource(main, interpolator(Map.of(), Map.of())).read());

        assertTrue(e.getMessage().contains("keystore references"), e.getMessage());
    }

    @Test
    public void includePathThatResolvesToNothingIsAnError() throws IOException {
        Path main = write("main.conf", "empty=\ninclude = ${empty}\n");

        ConfigException e = expectThrows(ConfigException.class,
                () -> new FileConfigSource(main, interpolator(Map.of(), Map.of())).read());

        assertTrue(e.getMessage().contains("include path is empty"), e.getMessage());
    }

    @Test
    public void nestedIncludeFallsBackToMainDirectoryWithWarning() throws IOException {
        Path common = write("common.conf", "c=1\n");
        Path sub = write("sub/sub.conf", "#include common.conf\n");
        Path main = write("main.conf", "#include sub/sub.conf\n");

        SourceContent content = new FileConfigSource(main).read();

        assertEquals(content.entries(), List.of(entry("c", "1", common, 1)));
        assertEquals(content.warnings().size(), 1);
        assertEquals(content.warnings().get(0).location(), new ConfigLocation(sub.toString(), 1));
    }

    @Test
    public void nestedIncludePrefersIncludingFileDirectory() throws IOException {
        write("common.conf", "c=main-dir\n");
        Path near = write("sub/common.conf", "c=sub-dir\n");
        Path main = write("main.conf", "#include sub/sub.conf\n");
        write("sub/sub.conf", "#include common.conf\n");

        SourceContent content = new FileConfigSource(main).read();

        assertEquals(content.entries(), List.of(entry("c", "sub-dir", near, 1)));
        assertTrue(content.warnings().isEmpty());
    }

    @Test
    public void includeOfDirectoryIsAnError() throws IOException {
        Files.createDirectories(dir.resolve("folder"));
        Path main = write("main.conf", "#include.required folder\n");

        ConfigException e = expectThrows(ConfigException.class, () -> new FileConfigSource(main).read());

        assertEquals(e.location().orElseThrow(), new ConfigLocation(main.toString(), 1));
        assertTrue(e.getMessage().contains("not a regular file"), e.getMessage());
    }

    @Test
    public void includeCycleThroughSymbolicLinkIsAnError() throws IOException {
        Path real = write("real/a.conf", "#include ../link/a.conf\n");
        try {
            Files.createSymbolicLink(dir.resolve("link"), real.getParent());
        } catch (UnsupportedOperationException | IOException e) {
            throw new SkipException("symbolic links not available: " + e);
        }

        ConfigException e = expectThrows(ConfigException.class, () -> new FileConfigSource(real).read());

        assertTrue(e.getMessage().contains("include cycle: " + real + " -> "), e.getMessage());
    }

    @Test
    public void acceptsAbsoluteIncludePath() throws IOException {
        Path other = write("other.conf", "o=1\n");
        Path main = write("main.conf", "include=" + other.toAbsolutePath() + "\n");

        assertEquals(new FileConfigSource(main).read().entries(), List.of(entry("o", "1", other, 1)));
    }

    @Test
    public void sameFileMayBeIncludedTwiceWithoutCycle() throws IOException {
        Path common = write("common.conf", "c=1\n");
        Path main = write("main.conf", "#include common.conf\n#include common.conf\n");

        assertEquals(new FileConfigSource(main).read().entries(), List.of(entry("c", "1", common, 1),
                entry("c", "1", common, 1)));
    }

    @Test
    public void hashIncludeMustBeFollowedByWhitespace() throws IOException {
        Path main = write("main.conf", "#includes are described in the manual\nk=v\n");

        assertEquals(new FileConfigSource(main).read().entries(), List.of(entry("k", "v", main, 2)));
    }

    @Test
    public void missingRequiredIncludeNamesFileAndLine() throws IOException {
        Path main = write("main.conf", "k=v\ninclude = missing.conf\n");

        ConfigException e = expectThrows(ConfigException.class, () -> new FileConfigSource(main).read());

        assertEquals(e.location().orElseThrow(), new ConfigLocation(main.toString(), 2));
        assertTrue(e.getMessage().startsWith(main + ":2: included file not found: "), e.getMessage());
        assertTrue(e.getMessage().contains("missing.conf"), e.getMessage());
    }

    @Test
    public void includeWithoutFileNameIsAnError() throws IOException {
        Path main = write("main.conf", "include =\n");

        ConfigException e = expectThrows(ConfigException.class, () -> new FileConfigSource(main).read());

        assertEquals(e.location().orElseThrow(), new ConfigLocation(main.toString(), 1));
    }

    @Test
    public void bareRequiredIncludeIsAnError() throws IOException {
        Path main = write("main.conf", "#include.required\n");

        ConfigException e = expectThrows(ConfigException.class, () -> new FileConfigSource(main).read());

        assertEquals(e.location().orElseThrow(), new ConfigLocation(main.toString(), 1));
    }

    @Test
    public void plainHashIncludeOfMissingFileIsSkippedWithWarning() throws IOException {
        Path main = write("main.conf", "#include ../conf/wrapper-license.conf\nk=v\n");

        SourceContent content = new FileConfigSource(main).read();

        assertEquals(content.entries(), List.of(entry("k", "v", main, 2)));
        assertEquals(content.warnings().size(), 1);
        ConfigWarning warning = content.warnings().get(0);
        assertEquals(warning.location(), new ConfigLocation(main.toString(), 1));
        assertTrue(warning.message().startsWith("optional include skipped, file not found: "), warning.message());
    }

    @Test
    public void plainHashIncludeProblemsAreWarnings() throws IOException {
        Files.createDirectories(dir.resolve("folder"));
        Path main = write("main.conf", "#include\n#include ${home}/x.conf\n#include folder\nk=v\n");

        SourceContent content = new FileConfigSource(main, interpolator(Map.of(), Map.of())).read();

        assertEquals(content.entries(), List.of(entry("k", "v", main, 4)));
        assertEquals(content.warnings().stream().map(w -> w.location().line()).toList(), List.of(1, 2, 3));
    }

    @Test
    public void plainHashIncludeCycleIsStillAnError() throws IOException {
        Path a = write("a.conf", "#include a.conf\n");

        expectThrows(ConfigException.class, () -> new FileConfigSource(a).read());
    }

    @Test
    public void unsupportedDirectiveWarningDoesNotEchoValue() throws IOException {
        Path main = write("main.conf", "#include.password=s3cret\n");

        SourceContent content = new FileConfigSource(main).read();

        assertEquals(content.warnings().size(), 1);
        assertTrue(content.warnings().get(0).message().endsWith(": #include.password"),
                content.warnings().get(0).message());
        assertFalse(content.warnings().get(0).toString().contains("s3cret"));
    }

    @Test
    public void includeCycleIsAnError() throws IOException {
        Path b = write("b.conf", "#include a.conf\n");
        Path a = write("a.conf", "#include b.conf\n");

        ConfigException e = expectThrows(ConfigException.class, () -> new FileConfigSource(a).read());

        assertEquals(e.location().orElseThrow(), new ConfigLocation(b.toString(), 1));
        assertTrue(e.getMessage().contains("include cycle"), e.getMessage());
    }

    @Test
    public void selfIncludeIsAnError() throws IOException {
        Path a = write("a.conf", "include = ./a.conf\n");

        ConfigException e = expectThrows(ConfigException.class, () -> new FileConfigSource(a).read());

        assertEquals(e.getMessage(), a + ":1: include cycle: " + a + " -> " + a);
    }

    @Test
    public void missingTopLevelFileIsAnError() {
        Path missing = dir.resolve("nope.conf");

        ConfigException e = expectThrows(ConfigException.class, () -> new FileConfigSource(missing).read());

        assertEquals(e.location().orElseThrow(), ConfigLocation.of(missing.toAbsolutePath().toString()));
        assertEquals(e.getMessage(), missing.toAbsolutePath() + ": configuration file not found");
        assertEquals(new FileConfigSource(missing).description(), missing.toAbsolutePath().toString());
    }

    @Test
    public void resultListsAreUnmodifiable() throws IOException {
        SourceContent content = new FileConfigSource(write("main.conf", "a=1\n")).read();

        assertThrows(UnsupportedOperationException.class, () -> content.entries().clear());
        assertThrows(UnsupportedOperationException.class, () -> content.warnings().clear());
    }

    // ---- value types ----

    @Test
    public void locationFormatsWithAndWithoutLine() {
        assertEquals(new ConfigLocation("f.conf", 3).toString(), "f.conf:3");
        assertEquals(ConfigLocation.of("f.conf").toString(), "f.conf");
        assertThrows(IllegalArgumentException.class, () -> new ConfigLocation("f.conf", -1));
    }

    @Test
    public void exceptionMessageStartsWithLocationWhenKnown() {
        assertEquals(new ConfigException(new ConfigLocation("f.conf", 2), "bad").getMessage(), "f.conf:2: bad");
        assertEquals(new ConfigException("bad").getMessage(), "bad");
        assertTrue(new ConfigException("bad").location().isEmpty());
    }

    // ---- helpers ----

    private Path write(String relative, String content) throws IOException {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file.toAbsolutePath().normalize();
    }

    private static FileConfigSource.Encoding declared(java.nio.charset.Charset charset) {
        return new FileConfigSource.Encoding(charset, "test.conf:7");
    }

    private static Interpolator interpolator(Map<String, String> systemProperties, Map<String, String> environment) {
        return new Interpolator(systemProperties, environment, false, ConfigKeys.registry());
    }

    /** The path with forward slashes, which configuration values may contain on every OS. */
    private static String slashes(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static Path fixture(String name) throws URISyntaxException {
        return Path.of(FileConfigSourceTest.class.getResource("/conf/parser/" + name).toURI());
    }

    private static ConfigEntry entry(String key, String value, Path file, int line) {
        return new ConfigEntry(key, value, new ConfigLocation(file.toString(), line));
    }
}
