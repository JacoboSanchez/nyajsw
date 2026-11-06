package io.github.jacobosanchez.nyajsw.launch;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import io.github.jacobosanchez.nyajsw.config.ConfigKeys;
import io.github.jacobosanchez.nyajsw.config.ConfigLoader;
import io.github.jacobosanchez.nyajsw.config.ConfigWarning;
import io.github.jacobosanchez.nyajsw.config.WrapperConfig;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/** The owner's installed Denodo Scheduler service configuration, unchanged, as a command line (plan Q1). */
public class DenodoSchedulerFixtureTest {

    private WrapperConfig config;
    private List<String> arguments;

    @BeforeClass
    public void load() throws Exception {
        Path file = Path.of(getClass().getResource("/conf/fixtures/denodo-scheduler/conf/scheduler/service.conf")
                .toURI());
        config = WrapperConfig.from(new ConfigLoader(Map.of(), Map.of(), true, ConfigKeys.registry()).load(file));
        arguments = new JavaCommandBuilder(Path.of("jdk21"), true)
                .build(config.java(), List.of("lib/denodo-commons-launcher-util.jar"), config.secrets()).arguments();
    }

    @Test
    public void escapesInJvmOptionsAreUndone() {
        assertTrue(arguments.contains("-Dderby.system.home=C:/Denodo/9/VDPSched94\\metadata\\scheduler"),
                arguments.toString());
        assertTrue(arguments.contains("-Djava.locale.providers=COMPAT,SPI"), arguments.toString());
    }

    @Test
    public void optionsWithBangsGlobsAndSemicolonsPassUnchanged() {
        assertTrue(arguments.contains("-Djdk.serialFilter=!org.mozilla.**;!org.apache.commons.**;"),
                arguments.toString());
        assertTrue(arguments.contains("-Ddenodo.rmi.server.hostname.default"), arguments.toString());
    }

    @Test
    public void commentedOutOptionIsAbsentAndTheGapIsHarmless() {
        assertTrue(arguments.stream().noneMatch(argument -> argument.contains("DENODO_OPTS_START")),
                arguments.toString());
        assertEquals(config.java().jvmOptions().size(), 9);
    }

    @Test
    public void mainClassArgumentsAndHeap() {
        assertEquals(arguments.subList(1, 3), List.of("-Xms3m", "-Xmx1024m"));
        int main = arguments.indexOf("com.denodo.util.launcher.Launcher");
        assertEquals(arguments.get(main + 1), "com.denodo.scheduler.core.server.Launcher");
        assertEquals(arguments.subList(arguments.size() - 2, arguments.size()),
                List.of("--class", "C:/Denodo/9/VDPSched94\\extensions\\dev\\target\\classes"));
        assertEquals(arguments.size() - main - 1, 29);
    }

    @Test
    public void warningsNameNothingAboutTheApplication() {
        List<String> messages = config.warnings().stream().map(ConfigWarning::message).toList();

        assertTrue(messages.stream().noneMatch(message -> message.startsWith("wrapper.java.")
                || message.startsWith("wrapper.app.")), messages.toString());
    }
}
