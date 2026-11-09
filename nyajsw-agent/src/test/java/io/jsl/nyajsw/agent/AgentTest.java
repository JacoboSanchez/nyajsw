package io.jsl.nyajsw.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** The agent in this JVM, against a fake wrapper end on a loopback socket; its exit is recorded, not taken. */
public class AgentTest {

    private static final String TOKEN = "0123456789abcdef";

    private ServerSocket wrapper;
    private CompletableFuture<Integer> exited;
    private ByteArrayOutputStream messageBytes;
    private PrintStream messages;

    @BeforeMethod
    public void setUp() throws IOException {
        wrapper = new ServerSocket(0, 1, AgentProtocol.loopback());
        wrapper.setSoTimeout(30_000);
        exited = new CompletableFuture<>();
        messageBytes = new ByteArrayOutputStream();
        messages = new PrintStream(messageBytes, true, StandardCharsets.UTF_8);
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws IOException {
        wrapper.close();
    }

    private String messages() {
        return messageBytes.toString(StandardCharsets.UTF_8);
    }

    private Thread startAgent(Agent.Exit exit) {
        Thread thread = Agent.start(Integer.toString(wrapper.getLocalPort()), TOKEN, exit, messages);
        assertNotNull(thread);
        assertTrue(thread.isDaemon(), "the agent must not keep the application JVM alive");
        assertEquals(thread.getName(), Agent.THREAD);
        return thread;
    }

    @Test(timeOut = 60_000)
    public void itIntroducesItselfAnswersPingsAndExitsOnStop() throws Exception {
        Thread agent = startAgent(exited::complete);
        try (Socket session = wrapper.accept()) {
            InputStream in = session.getInputStream();
            OutputStream out = session.getOutputStream();

            assertTrue(AgentProtocol.isHello(AgentProtocol.readLine(in), TOKEN));
            AgentProtocol.writeLine(out, AgentProtocol.ping(1));
            assertEquals(AgentProtocol.readLine(in), "PONG 1");
            AgentProtocol.writeLine(out, "RELOAD");
            assertEquals(AgentProtocol.readLine(in), AgentProtocol.UNKNOWN, "a newer wrapper can tell");
            AgentProtocol.writeLine(out, AgentProtocol.PING);
            assertEquals(AgentProtocol.readLine(in), AgentProtocol.PONG);
            assertFalse(exited.isDone());
            AgentProtocol.writeLine(out, AgentProtocol.STOP);
            assertEquals(AgentProtocol.readLine(in), AgentProtocol.STOPPING);
        }

        assertEquals(exited.get(30, TimeUnit.SECONDS), 0);
        agent.join(30_000);
        assertFalse(agent.isAlive());
        assertEquals(messages(), "");
    }

    @Test(timeOut = 60_000)
    public void aWrapperThatGoesAwayEndsTheAgentButNotTheApplication() throws Exception {
        Thread agent = startAgent(exited::complete);
        try (Socket session = wrapper.accept()) {
            AgentProtocol.readLine(session.getInputStream());
        }

        agent.join(30_000);
        assertFalse(agent.isAlive());
        assertFalse(exited.isDone());
    }

    @Test(timeOut = 60_000)
    public void anExitTheApplicationForbidsCostsOnlyTheAgent() throws Exception {
        Thread agent = startAgent(code -> {
            throw new SecurityException("exit forbidden");
        });
        try (Socket session = wrapper.accept()) {
            AgentProtocol.readLine(session.getInputStream());
            AgentProtocol.writeLine(session.getOutputStream(), AgentProtocol.STOP);
            agent.join(30_000);
        }

        assertFalse(agent.isAlive());
        assertTrue(messages().startsWith("nyajsw agent: stopped (java.lang.SecurityException)"), messages());
    }

    @Test
    public void aStopIsCarriedOutAlsoWhenItCannotBeAcknowledged() throws IOException {
        Agent agent = new Agent(1, TOKEN, exited::complete, messages, 1, 0);
        OutputStream closing = new OutputStream() {
            private int lines;

            @Override
            public void write(int b) throws IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                if (lines++ > 0) {
                    throw new IOException("the wrapper has gone"); // after its HELLO
                }
            }
        };

        agent.serve(new ByteArrayInputStream("STOP\n".getBytes(StandardCharsets.US_ASCII)), closing);

        assertEquals(exited.getNow(null), Integer.valueOf(0));
    }

    @Test
    public void aMessageCutOffByTheEndOfTheStreamIsNoStop() throws IOException {
        Agent agent = new Agent(1, TOKEN, exited::complete, messages, 1, 0);

        agent.serve(new ByteArrayInputStream("STOP".getBytes(StandardCharsets.US_ASCII)), new ByteArrayOutputStream());

        assertFalse(exited.isDone());
    }

    @Test(timeOut = 60_000)
    public void aWrapperThatCannotBeReachedIsGivenUpOn() throws Exception {
        int port = wrapper.getLocalPort();
        wrapper.close(); // nothing listens there any more

        new Agent(port, TOKEN, exited::complete, messages, 3, 10).run();

        assertFalse(exited.isDone());
        assertTrue(messages().startsWith("nyajsw agent: cannot reach the wrapper on port " + port), messages());
    }

    @Test
    public void withoutAPortOrATokenNothingStarts() {
        assertNull(Agent.start(null, TOKEN, exited::complete, messages));
        assertNull(Agent.start("not a port", TOKEN, exited::complete, messages));
        assertNull(Agent.start("0", TOKEN, exited::complete, messages));
        assertNull(Agent.start("70000", TOKEN, exited::complete, messages));
        assertNull(Agent.start("4000", null, exited::complete, messages));
        assertNull(Agent.start("4000", "", exited::complete, messages));
        assertTrue(messages().startsWith("nyajsw agent: no wrapper to connect to"), messages());
        assertFalse(messages().contains(TOKEN), "the token is never shown");
    }
}
