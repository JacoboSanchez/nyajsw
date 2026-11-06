package io.github.jacobosanchez.nyajsw.config;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class InterpolatorTest {

    private static final Map<String, String> SYS = Map.of("java.home", "C:/jdk21", "shared", "from-sys");
    private static final Map<String, String> ENV = Map.of("JAVA_HOME", "C:/jdk8", "shared", "from-env",
            "Path", "C:/Windows");

    private final Interpolator interpolator = new Interpolator(SYS, ENV, false, ConfigKeys.registry());

    // ---- sources and precedence ----

    @Test
    public void resolvesConfigurationKeysSystemPropertiesAndEnvironment() {
        Interpolator.Result result = interpolate(
                "app_home", "C:/app",
                "wrapper.java.classpath.1", "${app_home}/lib/*.jar",
                "wrapper.java.command", "${java.home}/bin/java",
                "wrapper.app.env.JAVA_HOME", "${JAVA_HOME}");

        assertEquals(values(result), List.of("C:/app", "C:/app/lib/*.jar", "C:/jdk21/bin/java", "C:/jdk8"));
        assertTrue(result.warnings().isEmpty(), result.warnings().toString());
    }

    @Test
    public void configurationWinsOverSystemPropertiesWhichWinOverEnvironment() {
        assertEquals(values(interpolate("shared", "from-conf", "a", "${shared}")).get(1), "from-conf");
        assertEquals(values(interpolate("a", "${shared}")), List.of("from-sys"));
        assertEquals(values(interpolate("a", "${JAVA_HOME}")), List.of("C:/jdk8"));
    }

    @Test
    public void prefixesSelectOneSource() {
        Interpolator.Result result = interpolate(
                "shared", "from-conf",
                "a", "${sys:shared}",
                "b", "${env:shared}",
                "c", "${sys:JAVA_HOME}",
                "d", "${env:java.home}");

        assertEquals(values(result), List.of("from-conf", "from-sys", "from-env", "${sys:JAVA_HOME}", "${env:java.home}"));
        assertEquals(result.warnings().size(), 2);
    }

    @Test
    public void referencesResolveRecursivelyWhateverTheOrder() {
        Interpolator.Result result = interpolate(
                "c", "${b}/c",
                "b", "${a}/b",
                "a", "/a");

        assertEquals(values(result), List.of("/a/b/c", "/a/b", "/a"));
    }

    @Test
    public void severalReferencesInOneValue() {
        assertEquals(values(interpolate("x", "1", "y", "2", "z", "${x}-${y}-${x}")).get(2), "1-2-1");
    }

    @Test
    public void environmentValuesAreNotResolvedAgain() {
        Interpolator literal = new Interpolator(Map.of(), Map.of("V", "${x}"), false, ConfigKeys.registry());

        Interpolator.Result result = literal.interpolate(entries("x", "1", "a", "${V}"));

        assertEquals(values(result), List.of("1", "${x}"));
    }

    @Test
    public void environmentCaseSensitivityIsConfigurable() {
        Interpolator windows = new Interpolator(Map.of(), ENV, true, ConfigKeys.registry());

        assertEquals(values(windows.interpolate(entries("a", "${PATH}", "b", "${env:java_home}"))),
                List.of("C:/Windows", "C:/jdk8"));
        Interpolator.Result posix = interpolator.interpolate(entries("a", "${PATH}"));
        assertEquals(values(posix), List.of("${PATH}"));
    }

    @Test
    public void exactEnvironmentNameWinsOverCaseInsensitiveMatch() {
        Interpolator windows = new Interpolator(Map.of(), Map.of("Path", "exact", "PATH", "other"), true,
                ConfigKeys.registry());

        assertEquals(values(windows.interpolate(entries("a", "${Path}", "b", "${PATH}"))), List.of("exact", "other"));
    }

    // ---- unresolved and malformed references ----

    @Test
    public void unresolvedReferenceIsKeptWithWarningNamingKeyAndLocation() {
        Interpolator.Result result = interpolate("wrapper.working.dir", "${missing}/work");

        assertEquals(values(result), List.of("${missing}/work"));
        assertEquals(result.warnings().size(), 1);
        ConfigWarning warning = result.warnings().get(0);
        assertEquals(warning.location(), new ConfigLocation("test.conf", 1));
        assertTrue(warning.message().contains("${missing}"), warning.message());
        assertTrue(warning.message().contains("wrapper.working.dir"), warning.message());
    }

    @Test
    public void unresolvedReferenceIsWarnedOnceEvenWhenReferencedAgain() {
        Interpolator.Result result = interpolate("a", "${missing}", "b", "${a}", "c", "${a}");

        assertEquals(values(result), List.of("${missing}", "${missing}", "${missing}"));
        assertEquals(result.warnings().size(), 1);
    }

    @Test
    public void emptyReferenceIsUnresolved() {
        Interpolator.Result result = interpolate("a", "x${}y");

        assertEquals(values(result), List.of("x${}y"));
        assertEquals(result.warnings().size(), 1);
    }

    @Test
    public void unterminatedReferenceIsKeptWithWarning() {
        Interpolator.Result result = interpolate("x", "1", "a", "${x}/${x");

        assertEquals(values(result).get(1), "1/${x");
        assertEquals(result.warnings().size(), 1);
        assertTrue(result.warnings().get(0).message().contains("without a closing"), result.warnings().toString());
    }

    @Test
    public void nestedReferenceResolvesOnlyTheInnerOneWithWarning() {
        Interpolator.Result result = interpolate("b", "x", "a", "${a${b}}");

        assertEquals(values(result).get(1), "${ax}");
        assertEquals(result.warnings().size(), 1);
        assertTrue(result.warnings().get(0).message().contains("nested"), result.warnings().toString());
    }

    @Test
    public void referenceWhoseNameIsAReferenceIsWarnedAbout() {
        Interpolator.Result result = interpolate("a", "${${JAVA_HOME}}");

        assertEquals(values(result), List.of("${C:/jdk8}"));
        assertEquals(result.warnings().size(), 1);
    }

    @Test
    public void windowsVariableNamesWithParenthesesResolve() {
        Interpolator windows = new Interpolator(Map.of(), Map.of("ProgramFiles(x86)", "C:/PF86"), true,
                ConfigKeys.registry());

        Interpolator.Result result = windows.interpolate(entries(
                "a", "${env:ProgramFiles(x86)}/Java",
                "b", "${ProgramFiles(x86)}/Java",
                "c", "${env:programfiles(x86)}/Java"));

        assertEquals(values(result), List.of("C:/PF86/Java", "C:/PF86/Java", "C:/PF86/Java"));
    }

    @Test
    public void missingPrefixedVariableWithParenthesesIsUnresolvedNotGroovy() {
        Interpolator.Result result = interpolate("a", "${env:ProgramFiles(x86)}/Java");

        assertEquals(values(result), List.of("${env:ProgramFiles(x86)}/Java"));
        assertEquals(result.warnings().size(), 1);
    }

    @Test
    public void definedKeysThatLookLikeCodeAreResolved() {
        Interpolator.Result result = interpolate("def.dir", "d", "new-home", "n", "a", "${def.dir}/${new-home}");

        assertEquals(values(result).get(2), "d/n");
    }

    @Test
    public void textWithoutReferencesIsUnchanged() {
        List<ConfigEntry> input = entries("a", "$ {x} $x {x} C:\\app\\\\");

        Interpolator.Result result = interpolator.interpolate(input);

        assertSame(result.entries().get(0), input.get(0));
        assertTrue(result.warnings().isEmpty());
    }

    // ---- errors ----

    @Test
    public void cycleIsAnErrorNamingTheChain() {
        ConfigException e = expectThrows(ConfigException.class,
                () -> interpolate("a", "${b}", "b", "${c}", "c", "${a}"));

        assertTrue(e.getMessage().contains("a -> b -> c -> a"), e.getMessage());
        assertEquals(e.location().orElseThrow(), new ConfigLocation("test.conf", 1));
    }

    @Test
    public void cycleThroughASecretKeyDoesNotShowTheChain() {
        ConfigException e = expectThrows(ConfigException.class,
                () -> interpolate("wrapper.app.password", "${hidden_ref}", "hidden_ref", "${wrapper.app.password}"));

        assertTrue(e.getMessage().contains("cycle"), e.getMessage());
        assertFalse(e.getMessage().contains("hidden_ref"), e.getMessage());
    }

    @Test
    public void selfReferenceSuggestsThePrefixes() {
        ConfigException e = expectThrows(ConfigException.class, () -> interpolate("JAVA_HOME", "${JAVA_HOME}"));

        assertTrue(e.getMessage().contains("${env:NAME}"), e.getMessage());
    }

    @DataProvider
    public Object[][] groovyExpressions() {
        return new Object[][] {
                {"${util.inquireCLI('Password')}"},
                {"${if (x) 'a' else 'b'}"},
                {"${new File('.').absolutePath}"},
                {"${\"a\".toUpperCase()}"},
                {"${def x = 1; x}"},
                {"${list[0]}"},
        };
    }

    @Test(dataProvider = "groovyExpressions")
    public void groovyExpressionIsAnError(String value) {
        ConfigException e = expectThrows(ConfigException.class, () -> interpolate("wrapper.app.parameter.1", value));

        assertTrue(e.getMessage().contains("Groovy"), e.getMessage());
        assertTrue(e.getMessage().contains("wrapper.app.parameter.1"), e.getMessage());
    }

    @DataProvider
    public Object[][] groovyAroundNestedReferences() {
        return new Object[][] {
                {"${util.f(\"${x}\")}"},
                {"${\"${x}\".trim()}"},
                {"${if (a) ${x}}"},
        };
    }

    @Test(dataProvider = "groovyAroundNestedReferences")
    public void groovyAroundANestedReferenceIsAnError(String value) {
        ConfigException e = expectThrows(ConfigException.class, () -> interpolate("x", "1", "a", value));

        assertTrue(e.getMessage().contains("Groovy"), e.getMessage());
    }

    @Test
    public void secretGroovyAroundANestedReferenceIsAnErrorWithoutDetails() {
        ConfigException e = expectThrows(ConfigException.class, () -> interpolate(
                "wrapper.ntservice.account", "svc",
                "wrapper.ntservice.password",
                "${util.inquireCLI(\"password for ${wrapper.ntservice.account}\")}"));

        assertTrue(e.getMessage().contains("Groovy"), e.getMessage());
        assertFalse(e.getMessage().contains("inquireCLI"), e.getMessage());
    }

    @Test
    public void namesThatOnlyStartLikeKeywordsAreNotGroovy() {
        Interpolator.Result result = interpolate("newline", "n", "ifx", "i", "definition", "d",
                "a", "${newline}${ifx}${definition}");

        assertEquals(values(result).get(3), "nid");
    }

    @Test
    public void duplicateKeysAreRejected() {
        expectThrows(IllegalArgumentException.class, () -> interpolate("a", "1", "a", "2"));
    }

    // ---- secrets ----

    @Test
    public void secretValuesAreResolvedButNeverShownInMessages() {
        Interpolator.Result result = interpolate(
                "pw_part", "s3",
                "wrapper.ntservice.password", "${pw_part}cret${hint_name}");

        assertEquals(values(result).get(1), "s3cret${hint_name}");
        String message = result.warnings().get(0).message();
        assertTrue(message.contains("wrapper.ntservice.password"), message);
        assertFalse(message.contains("hint_name"), message);

        ConfigException e = expectThrows(ConfigException.class,
                () -> interpolate("wrapper.app.password", "${util.inquireCLI('secret-hint')}"));
        assertFalse(e.getMessage().contains("secret-hint"), e.getMessage());
    }

    // ---- single texts such as include paths ----

    @Test
    public void resolveReportsUnresolvedNamesWithoutWarningAboutThem() {
        Map<String, ConfigEntry> config = Map.of("base", new ConfigEntry("base", "${java.home}", location(4)));

        Interpolator.Resolution resolution = interpolator.resolve("${base}/${missing}/${env:JAVA_HOME}", location(9),
                key -> Optional.ofNullable(config.get(key)));

        assertEquals(resolution.value(), "C:/jdk21/${missing}/C:/jdk8");
        assertEquals(resolution.unresolved(), List.of("missing"));
        assertFalse(resolution.complete());
        assertTrue(resolution.warnings().isEmpty(), resolution.warnings().toString());
    }

    @Test
    public void resolveCountsReferencesLeftUnresolvedInReferencedKeys() {
        Map<String, ConfigEntry> config = Map.of(
                "base", new ConfigEntry("base", "${middle}/${missing}", location(4)),
                "middle", new ConfigEntry("middle", "${deeper}", location(5)));

        Interpolator.Resolution resolution = interpolator.resolve("${base}/x", location(9),
                key -> Optional.ofNullable(config.get(key)));

        assertFalse(resolution.complete());
        assertEquals(resolution.value(), "${deeper}/${missing}/x");
        assertEquals(resolution.unresolved(), List.of("deeper", "missing"));
    }

    @Test
    public void resolveLeavesWarningsAboutReferencedKeysToInterpolation() {
        Map<String, ConfigEntry> config = Map.of("base", new ConfigEntry("base", "a${b", location(4)));

        Interpolator.Resolution resolution = interpolator.resolve("${base}/${x", location(9),
                key -> Optional.ofNullable(config.get(key)));

        assertEquals(resolution.value(), "a${b/${x");
        assertEquals(resolution.warnings().size(), 1);
        assertEquals(resolution.warnings().get(0).location(), location(9));
    }

    @Test
    public void resolveTellsWhetherASecretKeyWasRead() {
        Map<String, ConfigEntry> config = Map.of(
                "wrapper.ntservice.password", new ConfigEntry("wrapper.ntservice.password", "TopSecret123", location(1)),
                "via", new ConfigEntry("via", "x${wrapper.ntservice.password}", location(2)),
                "plain", new ConfigEntry("plain", "p", location(3)));

        assertTrue(interpolator.resolve("${wrapper.ntservice.password}.conf", location(9),
                key -> Optional.ofNullable(config.get(key))).usesSecret());
        assertTrue(interpolator.resolve("${via}.conf", location(9),
                key -> Optional.ofNullable(config.get(key))).usesSecret());
        assertFalse(interpolator.resolve("${plain}/${java.home}.conf", location(9),
                key -> Optional.ofNullable(config.get(key))).usesSecret());
    }

    @Test
    public void forCurrentProcessReadsThisJvm() {
        Interpolator current = Interpolator.forCurrentProcess();

        Interpolator.Resolution resolution = current.resolve("${sys:java.version}", location(1), key -> Optional.empty());

        assertEquals(resolution.value(), System.getProperty("java.version"));
    }

    // ---- helpers ----

    private Interpolator.Result interpolate(String... keysAndValues) {
        return interpolator.interpolate(entries(keysAndValues));
    }

    private static List<ConfigEntry> entries(String... keysAndValues) {
        List<ConfigEntry> entries = new ArrayList<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            entries.add(new ConfigEntry(keysAndValues[i], keysAndValues[i + 1], location(i / 2 + 1)));
        }
        return entries;
    }

    private static List<String> values(Interpolator.Result result) {
        return result.entries().stream().map(ConfigEntry::value).toList();
    }

    private static ConfigLocation location(int line) {
        return new ConfigLocation("test.conf", line);
    }
}
