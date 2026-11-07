package io.github.jacobosanchez.nyajsw.agentlink;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import io.github.jacobosanchez.nyajsw.TestConfigs;
import io.github.jacobosanchez.nyajsw.agent.Agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class AgentJarTest {

    private Path dir;

    @BeforeMethod
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("nyajsw-agent-jar").toRealPath();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws IOException {
        TestConfigs.delete(dir);
    }

    private Path jar(String name, String premainClass) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (premainClass != null) {
            manifest.getMainAttributes().put(new Attributes.Name("Premain-Class"), premainClass);
        }
        Path jar = dir.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.flush();
        }
        return jar;
    }

    @Test
    public void theJarTheAgentCameFromWhenItNamesTheAgent() throws IOException {
        Path agent = jar("nyajsw-agent-0.1.0.jar", Agent.class.getName());

        assertEquals(AgentJar.locate(agent), Optional.of(agent));
    }

    @Test
    public void otherwiseTheAgentJarBesideIt() throws IOException {
        Path wrapper = jar("nyajsw.jar", null); // a wrapper jar that bundles the agent classes
        assertEquals(AgentJar.locate(wrapper), Optional.empty());

        Path beside = jar(AgentJar.FILE_NAME, Agent.class.getName());
        assertEquals(AgentJar.locate(wrapper), Optional.of(beside));
        jar(AgentJar.FILE_NAME, "some.other.Agent");
        assertEquals(AgentJar.locate(wrapper), Optional.empty(), "a jar of that name that is not the agent");
    }

    @Test
    public void classesFromADirectoryHaveNoJar() throws IOException {
        assertEquals(AgentJar.locate(Files.createDirectories(dir.resolve("classes"))), Optional.empty());
        assertFalse(AgentJar.isAgentJar(dir.resolve("missing.jar")));
        assertTrue(AgentJar.isAgentJar(jar("a.jar", Agent.class.getName())));
    }
}
