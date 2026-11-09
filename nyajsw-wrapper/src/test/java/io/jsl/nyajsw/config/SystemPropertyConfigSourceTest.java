package io.jsl.nyajsw.config;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;

public class SystemPropertyConfigSourceTest {

    @Test
    public void readsEveryPropertyInKeyOrderTrimmed() {
        SourceContent content = new SystemPropertyConfigSource(Map.of(
                "wrapper.tmp.path", " C:/tmp\u3000",
                "java.home", "C:/jdk",
                "wrapper.app.env.A", "a")).read();

        assertEquals(content.entries(), List.of(
                new ConfigEntry("java.home", "C:/jdk", ConfigLocation.of("system property java.home")),
                new ConfigEntry("wrapper.app.env.A", "a", ConfigLocation.of("system property wrapper.app.env.A")),
                new ConfigEntry("wrapper.tmp.path", "C:/tmp", ConfigLocation.of("system property wrapper.tmp.path"))));
        assertTrue(content.warnings().isEmpty());
    }

    @Test
    public void onlyWrapperPropertiesAreConfigurationKeys() {
        assertTrue(SystemPropertyConfigSource.isConfigurationKey("wrapper.tmp.path"));
        assertFalse(SystemPropertyConfigSource.isConfigurationKey("wrapper."));
        assertFalse(SystemPropertyConfigSource.isConfigurationKey("wrapperx"));
        assertFalse(SystemPropertyConfigSource.isConfigurationKey("java.home"));
    }

    @Test
    public void describesItself() {
        assertEquals(new SystemPropertyConfigSource(Map.of()).description(), "wrapper JVM system properties");
    }
}
