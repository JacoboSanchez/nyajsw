package io.jsl.nyajsw.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The agent as {@code -javaagent} in a real JVM: a jar like the one the build makes (the manifest's
 * {@code Premain-Class}), built from the compiled main classes since the tests run before packaging.
 */
public class AgentJarTest {

    private Path dir;

    @BeforeMethod
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("nyajsw-agent-jar");
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    private Path agentJar() throws IOException {
        Path classes = Path.of(System.getProperty("nyajsw.test.mainClassesDir"));
        if (!Files.isRegularFile(classes.resolve("io/jsl/nyajsw/agent/Agent.class"))) {
            throw new SkipException("the agent's main classes are not compiled");
        }
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(new Attributes.Name("Premain-Class"), Agent.class.getName());
        Path jar = dir.resolve("nyajsw-agent.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest);
             Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, out);
                out.closeEntry();
            }
        }
        return jar;
    }

    private static String testClasses() throws URISyntaxException {
        return Path.of(ParkedApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    @Test(timeOut = 120_000)
    public void aStopEndsTheApplicationWithExitCodeZeroAndItsShutdownHooksRun() throws Exception {
        String token = AgentProtocol.newToken(new SecureRandom());
        try (ServerSocket wrapper = new ServerSocket(0, 1, AgentProtocol.loopback())) {
            wrapper.setSoTimeout(30_000);
            ProcessBuilder builder = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-javaagent:" + agentJar(), "-D" + AgentProtocol.PORT_PROPERTY + "=" + wrapper.getLocalPort(),
                    "-cp", testClasses(), ParkedApplication.class.getName());
            builder.environment().put(AgentProtocol.TOKEN_VARIABLE, token);
            builder.redirectErrorStream(true);
            Process application = builder.start();
            try (Socket session = wrapper.accept()) {
                InputStream in = session.getInputStream();
                OutputStream out = session.getOutputStream();

                assertTrue(AgentProtocol.isHello(AgentProtocol.readLine(in), token));
                AgentProtocol.writeLine(out, AgentProtocol.ping(1));
                assertEquals(AgentProtocol.readLine(in), "PONG 1");
                AgentProtocol.writeLine(out, AgentProtocol.STOP);
                assertEquals(AgentProtocol.readLine(in), AgentProtocol.STOPPING);
            } finally {
                if (!application.waitFor(30, TimeUnit.SECONDS)) {
                    application.destroyForcibly().waitFor(30, TimeUnit.SECONDS); // it holds the jar open
                }
            }

            String output = new String(application.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(application.exitValue(), 0, output);
            List<String> lines = output.lines().toList();
            assertTrue(lines.contains("parked") && lines.contains("hook ran"), output);
        }
    }

    @Test(timeOut = 120_000)
    public void withoutAWrapperTheApplicationRunsOnAndSaysSo() throws Exception {
        assertRunsOnAndSays(List.of(), "nyajsw agent: no wrapper to connect to");
    }

    @Test(timeOut = 120_000)
    public void aSecurityManagerThatForbidsTheAgentCostsOnlyTheAgent() throws Exception {
        if (Runtime.version().feature() >= 24) {
            throw new SkipException("this JVM cannot install a security manager any more");
        }
        // The default policy grants no agent jar the right to read nyajsw.agent.port.
        assertRunsOnAndSays(List.of("-Djava.security.manager", "-D" + AgentProtocol.PORT_PROPERTY + "=1"),
                "nyajsw agent: disabled (java.security.AccessControlException)");
    }

    /**
     * Starts {@link ParkedApplication} with the agent and {@code options}: it must start, and the agent
     * say {@code said}.
     */
    private void assertRunsOnAndSays(List<String> options, String said) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java")
                .toString(), "-javaagent:" + agentJar()));
        command.addAll(options);
        command.addAll(List.of("-cp", testClasses(), ParkedApplication.class.getName()));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put(AgentProtocol.TOKEN_VARIABLE, "abc");
        if (options.isEmpty()) {
            builder.environment().remove(AgentProtocol.TOKEN_VARIABLE);
        }
        Process application = builder.start();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(application.getInputStream(),
                    StandardCharsets.UTF_8));
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null && !line.equals("parked")) {
                lines.add(line);
            }
            assertEquals(line, "parked", "the application started: " + lines);
            assertTrue(lines.stream().anyMatch(seen -> seen.startsWith(said)), lines.toString());
            assertTrue(application.isAlive());
        } finally {
            application.destroyForcibly().waitFor(60, TimeUnit.SECONDS);
        }
    }
}
