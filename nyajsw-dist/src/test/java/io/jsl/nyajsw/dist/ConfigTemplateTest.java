package io.jsl.nyajsw.dist;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import io.jsl.nyajsw.config.ConfigKeys;
import io.jsl.nyajsw.config.ConfigLoader;
import io.jsl.nyajsw.config.KeyDefault;
import io.jsl.nyajsw.config.KeySpec;
import io.jsl.nyajsw.config.WrapperConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.testng.annotations.Test;

/**
 * The shipped {@code conf/wrapper.conf} template: it loads without warnings as shipped and with any
 * one of its commented-out settings switched on, and a commented-out value is the key's fixed default
 * where the key has one, as the template says.
 */
public class ConfigTemplateTest {

    private static final Path TEMPLATE = Path.of("src", "main", "dist", "conf", "wrapper.conf");
    private static final Pattern COMMENTED_SETTING = Pattern.compile("^#(wrapper\\.[^=\\s]+)=(.*)$");
    private static final String MAIN_CLASS = "wrapper.java.app.mainclass=";

    private static List<String> template() throws IOException {
        return Files.readAllLines(TEMPLATE, StandardCharsets.UTF_8);
    }

    private static WrapperConfig load(Path dir, List<String> lines) throws IOException {
        Path conf = Files.write(dir.resolve("wrapper.conf"), lines, StandardCharsets.UTF_8);
        return WrapperConfig.from(new ConfigLoader(Map.of(), Map.of(), false, ConfigKeys.registry()).load(conf));
    }

    @Test
    public void theTemplateLoadsWithoutWarnings() throws IOException {
        Path dir = Files.createTempDirectory("nyajsw-template");
        try {
            WrapperConfig config = load(dir, template());

            assertEquals(config.warnings(), List.of());
            assertEquals(config.service().name(), Optional.of("example"));
        } finally {
            delete(dir);
        }
    }

    @Test
    public void everyCommentedOutSettingLoadsWithoutWarningsWhenSwitchedOn() throws IOException {
        List<String> template = template();
        List<String> problems = new ArrayList<>();
        int settings = 0;
        Path dir = Files.createTempDirectory("nyajsw-template");
        try {
            for (int i = 0; i < template.size(); i++) {
                Matcher setting = COMMENTED_SETTING.matcher(template.get(i));
                if (!setting.matches()) {
                    continue;
                }
                settings++;
                List<String> lines = new ArrayList<>(template);
                lines.set(i, template.get(i).substring(1));
                if (setting.group(1).equals("wrapper.java.app.jar")) { // instead of the main class, as it says
                    lines.replaceAll(line -> line.startsWith(MAIN_CLASS) ? "#" + line : line);
                }
                try {
                    WrapperConfig config = load(dir, lines);
                    if (!config.warnings().isEmpty()) {
                        problems.add(template.get(i) + ": " + config.warnings());
                    }
                } catch (RuntimeException e) {
                    problems.add(template.get(i) + ": " + e);
                }
            }
        } finally {
            delete(dir);
        }

        assertTrue(settings > 50, "commented-out settings found: " + settings);
        assertTrue(problems.isEmpty(), String.join(System.lineSeparator(), problems));
    }

    @Test
    public void aCommentedOutValueIsTheFixedDefaultWhereThereIsOne() throws IOException {
        List<String> template = template();
        List<String> mismatches = new ArrayList<>();
        Path dir = Files.createTempDirectory("nyajsw-template");
        try {
            List<Object> shipped = settings(load(dir, template));
            for (int i = 0; i < template.size(); i++) {
                String line = template.get(i);
                Matcher setting = COMMENTED_SETTING.matcher(line);
                if (!setting.matches()) {
                    continue;
                }
                KeySpec spec = ConfigKeys.registry().lookup(setting.group(1)).orElseThrow(
                        () -> new AssertionError("not a registered key: " + line));
                if (!(spec.defaultValue() instanceof KeyDefault.Constant constant)) {
                    continue;
                }
                if (!constant.value().equals(setting.group(2))) {
                    mismatches.add(line + " (default " + constant.value() + ")");
                    continue;
                }
                // Written out, a default must change nothing.
                List<String> lines = new ArrayList<>(template);
                lines.set(i, line.substring(1));
                if (!settings(load(dir, lines)).equals(shipped)) {
                    mismatches.add(line + " changes the settings when written");
                }
            }
        } finally {
            delete(dir);
        }

        assertTrue(mismatches.isEmpty(), String.join(System.lineSeparator(), mismatches));
    }

    /** The typed settings of {@code config}, which are records. */
    private static List<Object> settings(WrapperConfig config) {
        return List.of(config.java(), config.service(), config.log(), config.lifecycle(), config.probes());
    }

    @Test
    public void theTemplateIsPlainText() throws IOException {
        byte[] bytes = Files.readAllBytes(TEMPLATE);
        String text = new String(bytes, StandardCharsets.UTF_8);

        assertFalse(text.startsWith("﻿"), "no byte order mark");
        assertFalse(text.contains("\t"), "no tabs");
        assertTrue(text.chars().allMatch(c -> c < 0x80), "ASCII only, so that any code page reads it alike");
    }

    private static void delete(Path dir) throws IOException {
        try (var paths = Files.walk(dir)) {
            for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
