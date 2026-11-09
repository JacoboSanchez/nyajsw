package io.jsl.nyajsw.config;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;

public class ConfigDumpTest {

    @Test
    public void listsEveryKeyWithLayerAndLocationAndHidesSecrets() {
        LoadedConfig config = new LoadedConfig(Path.of("w.conf").toAbsolutePath(), List.of(
                new ConfigEntry("wrapper.ntservice.password", "s3cret", new ConfigLocation("w.conf", 1)),
                new ConfigEntry("copy", "pw=s3cret;", new ConfigLocation("w.conf", 2)),
                new ConfigEntry("wrapper.startup.timeout", "30", ConfigLocation.of("built-in default"))),
                Map.of("wrapper.ntservice.password", ConfigLayer.FILE, "copy", ConfigLayer.FILE,
                        "wrapper.startup.timeout", ConfigLayer.DEFAULT),
                List.of());

        String dump = ConfigDump.render(ConfigKeys.registry(), config);

        assertEquals(dump.lines().toList(), List.of(
                "wrapper.ntservice.password = ****  [file, w.conf:1]",
                "copy = pw=****;  [file, w.conf:2]",
                "wrapper.startup.timeout = 30  [default, built-in default]"));
        assertFalse(dump.contains("s3cret"));
    }

    @Test
    public void overlappingSecretsAreHiddenWhole() {
        LoadedConfig config = new LoadedConfig(Path.of("w.conf").toAbsolutePath(), List.of(
                new ConfigEntry("wrapper.app.password", "abcd", ConfigLocation.of("x")),
                new ConfigEntry("wrapper.ntservice.password", "cdef", ConfigLocation.of("x"))),
                Map.of("wrapper.app.password", ConfigLayer.FILE, "wrapper.ntservice.password", ConfigLayer.FILE),
                List.of());

        assertEquals(new SecretMasker(ConfigKeys.registry(), config).mask("other", "<abcdef>"), "<****>");
    }

    @Test
    public void copiesMadeByInterpolationAreMaskedEndToEnd() throws java.io.IOException {
        Path dir = java.nio.file.Files.createTempDirectory("nyajsw-dump-test");
        try {
            Path file = java.nio.file.Files.writeString(dir.resolve("wrapper.conf"), "PW=Tr0ub4dor\n"
                    + "wrapper.ntservice.password=${PW}\n"
                    + "wrapper.app.parameter.1=--password=${wrapper.ntservice.password}\n");
            LoadedConfig config = new ConfigLoader(Map.of(), Map.of(), false, ConfigKeys.registry()).load(file);

            String dump = ConfigDump.render(ConfigKeys.registry(), config);

            assertFalse(dump.contains("Tr0ub4dor"), dump);
            assertTrue(dump.contains("wrapper.app.parameter.1 = --password=****"), dump);
            assertTrue(dump.contains("PW = ****"), dump);
        } finally {
            try (java.util.stream.Stream<Path> paths = java.nio.file.Files.walk(dir)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    java.nio.file.Files.delete(path);
                }
            }
        }
    }

    @Test
    public void aSecretWithASlashIsMaskedInAPathAsWindowsShowsIt() {
        LoadedConfig config = new LoadedConfig(Path.of("w.conf").toAbsolutePath(), List.of(
                new ConfigEntry("wrapper.app.password", "ab/cd", ConfigLocation.of("x"))),
                Map.of("wrapper.app.password", ConfigLayer.FILE), List.of());

        SecretMasker masker = new SecretMasker(ConfigKeys.registry(), config);

        assertEquals(masker.maskText("C:\\flags\\ab\\cd.ready does not exist"), "C:\\flags\\****.ready does not exist");
        assertEquals(masker.maskText("/flags/ab/cd.ready"), "/flags/****.ready");
    }

    @Test
    public void longerSecretsAreMaskedWholeAndEmptySecretsMaskNothing() {
        LoadedConfig config = new LoadedConfig(Path.of("w.conf").toAbsolutePath(), List.of(
                new ConfigEntry("wrapper.app.password", "abc", ConfigLocation.of("x")),
                new ConfigEntry("wrapper.ntservice.password", "abcdef", ConfigLocation.of("x")),
                new ConfigEntry("wrapper.jmx.rmi.password", "", ConfigLocation.of("x")),
                new ConfigEntry("other", "abcdef-abc", ConfigLocation.of("x"))),
                Map.of("wrapper.app.password", ConfigLayer.FILE, "wrapper.ntservice.password", ConfigLayer.FILE,
                        "wrapper.jmx.rmi.password", ConfigLayer.FILE, "other", ConfigLayer.FILE),
                List.of());

        SecretMasker masker = new SecretMasker(ConfigKeys.registry(), config);

        assertEquals(masker.mask("other", "abcdef-abc"), "****-****");
        assertEquals(masker.mask("other", "plain"), "plain");
        assertEquals(masker.mask("other", "xabcabcx"), "x****x");
        assertEquals(masker.mask("wrapper.unknown.password", "anything"), SecretMasker.MASK);
    }
}
