package io.github.jacobosanchez.nyajsw.config;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;

public class ConfigValidatorTest {

    private static final KeySpec SUPPORTED_INT = KeySpec.nyajsw("wrapper.test.count", KeyType.INTEGER, KeyCategory.LIFECYCLE)
            .status(KeyStatus.SUPPORTED).description("X").build();

    private final ConfigValidator validator = new ConfigValidator(ConfigKeys.registry());

    @Test
    public void plannedIgnoredAndUnsupportedKeysWarnOncePerDeclaration() {
        ConfigValidator.Result result = validator.validate(config(
                "wrapper.ping.interval", "5", ConfigLayer.FILE,
                "wrapper.java.mainclass", "org.rzo.yajsw.app.WrapperJVMMain", ConfigLayer.FILE,
                "wrapper.java.monitor.deadlock", "true", ConfigLayer.FILE,
                "wrapper.java.monitor.heap", "true", ConfigLayer.FILE));

        assertTrue(result.errors().isEmpty(), result.errors().toString());
        List<String> messages = result.warnings().stream().map(ConfigWarning::message).toList();
        assertEquals(messages, List.of(
                "wrapper.ping.interval is not implemented yet in this version of nyajsw; it has no effect",
                "wrapper.java.mainclass is ignored by nyajsw. " + ConfigKeys.JAVA_MAINCLASS.notes(),
                "wrapper.java.monitor.<setting> (2 keys) is not supported by nyajsw; it has no effect"));
        assertEquals(result.warnings().get(2).location(), new ConfigLocation("wrapper.conf", 3));
    }

    @Test
    public void fatalKeyIsAnError() {
        ConfigValidator.Result result = validator.validate(config("wrapper.app.account", "DOMAIN\\svc", ConfigLayer.FILE));

        assertEquals(result.errors().size(), 1);
        assertTrue(result.errors().get(0).getMessage().contains("wrapper.app.account is not supported by nyajsw and"
                + " cannot be ignored"), result.errors().get(0).getMessage());
        expectThrows(ConfigException.class, result::throwIfErrors);
    }

    @Test
    public void configurationScriptIsAnError() {
        ConfigValidator.Result result = validator.validate(config(
                "wrapper.config.script.decryptor", "scripts/decrypt.gv", ConfigLayer.FILE));

        assertEquals(result.errors().size(), 1);
        assertTrue(result.errors().get(0).getMessage().contains("wrapper.config.script.<name> (1 key) is not supported by"
                + " nyajsw and cannot be ignored"), result.errors().get(0).getMessage());
    }

    @Test
    public void emptyFatalKeyOnlyWarns() {
        ConfigValidator.Result result = validator.validate(config(
                "wrapper.app.account", "", ConfigLayer.FILE,
                "wrapper.app.password", "", ConfigLayer.FILE));

        assertTrue(result.errors().isEmpty(), result.errors().toString());
        assertEquals(result.warnings().stream().map(ConfigWarning::message).toList(), List.of(
                "wrapper.app.account is not supported by nyajsw; it is empty and has no effect",
                "wrapper.app.password is not supported by nyajsw; it is empty and has no effect"));
    }

    @Test
    public void errorsComeInConfigurationOrder() {
        ConfigValidator custom = new ConfigValidator(new KeyRegistry(List.of(SUPPORTED_INT, ConfigKeys.APP_ACCOUNT)));

        ConfigValidator.Result result = custom.validate(config(
                "wrapper.app.account", "svc", ConfigLayer.FILE,
                "wrapper.test.count", "many", ConfigLayer.FILE));

        assertEquals(result.errors().stream().map(e -> e.location().orElseThrow().line()).toList(), List.of(1, 2));
    }

    @Test
    public void plannedKeyWithAnInvalidValueOnlyWarns() {
        ConfigValidator.Result result = validator.validate(
                config("wrapper.ntservice.starttype", "SOMETIMES", ConfigLayer.FILE));

        assertTrue(result.errors().isEmpty(), result.errors().toString());
        assertTrue(result.warnings().stream().anyMatch(w -> w.message().startsWith("invalid value 'SOMETIMES' for"
                + " wrapper.ntservice.starttype: expected one of ")), result.warnings().toString());
    }

    @Test
    public void unknownWrapperKeysWarnAndOtherKeysAreVariables() {
        ConfigValidator.Result result = validator.validate(config(
                "wrapper.no.such.key", "1", ConfigLayer.FILE,
                "APP_HOME", "C:/app", ConfigLayer.FILE));

        assertEquals(result.warnings().stream().map(ConfigWarning::message).toList(),
                List.of("unknown key wrapper.no.such.key; it has no effect"));
        result.throwIfErrors();
    }

    @Test
    public void defaultsAreNeverJudged() {
        ConfigValidator.Result result = validator.validate(config(
                "wrapper.control", "TIGHT", ConfigLayer.DEFAULT,
                "wrapper.agent.enabled", "true", ConfigLayer.DEFAULT));

        assertTrue(result.warnings().isEmpty(), result.warnings().toString());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    public void invalidValuesOfSupportedKeysAreAggregatedErrors() {
        KeySpec supportedDuration = KeySpec.nyajsw("wrapper.test.wait", KeyType.DURATION, KeyCategory.LIFECYCLE)
                .status(KeyStatus.SUPPORTED).description("X").build();
        ConfigValidator custom = new ConfigValidator(new KeyRegistry(List.of(SUPPORTED_INT, supportedDuration,
                ConfigKeys.TRAY_PORT)));
        ConfigValidator.Result result = custom.validate(config(
                "wrapper.test.count", "many", ConfigLayer.FILE,
                "wrapper.test.wait", "soon", ConfigLayer.SYSTEM_PROPERTY,
                "wrapper.tray.port", "not checked", ConfigLayer.FILE));

        assertEquals(result.errors().size(), 2);
        assertEquals(result.errors().get(0).getMessage(),
                "wrapper.conf:1: invalid value 'many' for wrapper.test.count: expected a whole number");
        ConfigException all = expectThrows(ConfigException.class, result::throwIfErrors);
        assertEquals(all.problems().size(), 2);
        assertTrue(all.getMessage().startsWith("2 configuration errors:"), all.getMessage());
    }

    @Test
    public void emptyValueOfATypedKeyMeansNotSet() {
        ConfigValidator custom = new ConfigValidator(new KeyRegistry(List.of(SUPPORTED_INT)));

        assertTrue(custom.validate(config("wrapper.test.count", "", ConfigLayer.FILE)).errors().isEmpty());
    }

    @Test
    public void invalidSecretValueIsNotShownAndSecretsAreMaskedElsewhere() {
        KeySpec secretInt = KeySpec.nyajsw("wrapper.test.pin.password", KeyType.INTEGER, KeyCategory.SERVICE)
                .status(KeyStatus.SUPPORTED).secret().description("X").build();
        ConfigValidator custom = new ConfigValidator(new KeyRegistry(List.of(secretInt, SUPPORTED_INT)));

        ConfigValidator.Result result = custom.validate(config(
                "wrapper.test.pin.password", "s3cret", ConfigLayer.FILE,
                "wrapper.test.count", "x-s3cret-x", ConfigLayer.FILE));

        assertEquals(result.errors().size(), 2);
        for (ConfigException error : result.errors()) {
            assertFalse(error.getMessage().contains("s3cret"), error.getMessage());
        }
        assertTrue(result.errors().get(1).getMessage().contains("'x-****-x'"), result.errors().get(1).getMessage());
    }

    @Test
    public void secretSetBySystemPropertyWarnsWithoutValue() {
        ConfigValidator.Result result = validator.validate(config(
                "wrapper.ntservice.password", "s3cret", ConfigLayer.SYSTEM_PROPERTY,
                "wrapper.ntservice.account", "svc", ConfigLayer.SYSTEM_PROPERTY));

        List<String> messages = result.warnings().stream().map(ConfigWarning::message).toList();
        assertTrue(messages.stream().anyMatch(m -> m.startsWith("wrapper.ntservice.password is set by a system property")),
                messages.toString());
        assertTrue(messages.stream().noneMatch(m -> m.startsWith("wrapper.ntservice.account is set by")), messages.toString());
        assertTrue(messages.stream().noneMatch(m -> m.contains("s3cret")), messages.toString());
    }

    @Test
    public void unknownSecretSetBySystemPropertyAlsoWarns() {
        ConfigValidator.Result result = validator.validate(config("wrapper.foo.password", "x", ConfigLayer.SYSTEM_PROPERTY));

        assertEquals(result.warnings().stream().map(ConfigWarning::message).toList(), List.of(
                "wrapper.foo.password is set by a system property on the wrapper command line, which other local"
                        + " users can read; set it in the configuration file instead",
                "unknown key wrapper.foo.password; it has no effect"));
    }

    @Test
    public void realisticConfigurationLoadsWithWarningsOnly() {
        ConfigValidator.Result result = validator.validate(config(
                "wrapper.java.app.mainclass", "com.example.Main", ConfigLayer.FILE,
                "wrapper.java.additional.1", "-Xss1m", ConfigLayer.FILE,
                "wrapper.java.additional.2", "-Dx=y", ConfigLayer.FILE,
                "wrapper.ntservice.starttype", "DELAY_START", ConfigLayer.FILE,
                "wrapper.startup.timeout", "90", ConfigLayer.FILE,
                "wrapper.tray", "true", ConfigLayer.FILE));

        result.throwIfErrors();
        // The application keys are supported since console mode (P2.5), the supervision keys since it
        // runs through the supervisor (P3.6), so they no longer warn.
        assertEquals(result.warnings().stream().map(ConfigWarning::message).toList(), List.of(
                "wrapper.ntservice.starttype is not implemented yet in this version of nyajsw; it has no effect",
                "wrapper.tray is not supported by nyajsw; it has no effect"));
    }

    /** Builds a LoadedConfig from (key, value, layer) triples; locations are wrapper.conf:index. */
    private static LoadedConfig config(Object... triples) {
        List<ConfigEntry> entries = new ArrayList<>();
        Map<String, ConfigLayer> layers = new LinkedHashMap<>();
        for (int i = 0; i < triples.length; i += 3) {
            String key = (String) triples[i];
            entries.add(new ConfigEntry(key, (String) triples[i + 1], new ConfigLocation("wrapper.conf", i / 3 + 1)));
            layers.put(key, (ConfigLayer) triples[i + 2]);
        }
        return new LoadedConfig(Path.of("wrapper.conf").toAbsolutePath(), entries, layers, List.of());
    }
}
