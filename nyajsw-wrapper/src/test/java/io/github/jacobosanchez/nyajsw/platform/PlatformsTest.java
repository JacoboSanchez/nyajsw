package io.github.jacobosanchez.nyajsw.platform;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import io.github.jacobosanchez.nyajsw.platform.unsupported.UnsupportedPlatform;
import io.github.jacobosanchez.nyajsw.platform.windows.WindowsPlatform;

import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.Test;

public class PlatformsTest {

    @Test
    public void aHostNoPlatformClaimsGetsTheConsoleOnlyFallback() {
        for (String osName : List.of("Linux", "Mac OS X", "FreeBSD", "")) {
            assertTrue(Platforms.forOsName(osName) instanceof UnsupportedPlatform, osName);
        }
        assertEquals(Platforms.forOsName("Linux").name(), "Linux");
    }

    @Test
    public void windowsHasItsPlatform() {
        for (String osName : List.of("Windows 10", "Windows Server 2022", "Windows 11")) {
            Platform platform = Platforms.forOsName(osName);

            assertTrue(platform instanceof WindowsPlatform, osName);
            assertEquals(platform.name(), osName);
            assertTrue(platform.serviceController() != null);
        }
    }

    @Test
    public void theFirstRegisteredPlatformThatClaimsTheHostIsChosen() {
        Platform windows = mock(Platform.class);
        Platform other = mock(Platform.class);
        List<String> builtFor = new ArrayList<>();
        List<Platforms.Entry> entries = List.of(
                new Platforms.Entry(HostOs::isWindows, osName -> {
                    builtFor.add(osName);
                    return windows;
                }),
                new Platforms.Entry(osName -> true, osName -> other));

        assertSame(Platforms.forOsName("Windows Server 2022", entries), windows);
        assertSame(Platforms.forOsName("Linux", entries), other);
        assertEquals(builtFor, List.of("Windows Server 2022"), "only the chosen platform is built");
    }

    @Test
    public void withoutEntriesEveryHostGetsTheFallback() {
        assertTrue(Platforms.forOsName("Windows 11", List.of()) instanceof UnsupportedPlatform);
    }

    @Test
    public void thisHostHasANamedPlatform() {
        assertFalse(Platforms.forThisHost().name().isBlank());
    }
}
