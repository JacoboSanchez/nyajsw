package io.github.jacobosanchez.nyajsw.agentlink;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import io.github.jacobosanchez.nyajsw.agent.AgentProtocol;
import io.github.jacobosanchez.nyajsw.config.LogLevel;
import io.github.jacobosanchez.nyajsw.log.WrapperLog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** The wrapper's end of the agent channel against {@link FakeAgent}s on real loopback sockets. */
public class AgentListenerTest {

    private static final Duration SECOND = Duration.ofSeconds(1);

    private ByteArrayOutputStream logBytes;
    private WrapperLog log;
    private AgentListener listener;

    @BeforeMethod
    public void setUp() throws IOException {
        logBytes = new ByteArrayOutputStream();
        log = WrapperLog.console(new PrintStream(logBytes, true, StandardCharsets.UTF_8), LogLevel.INFO);
        listener = AgentListener.open("jvm 1", log, new SecureRandom(), Duration.ofMillis(500), Duration.ofMinutes(5));
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        listener.close();
        log.close();
    }

    private String logged() {
        return logBytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void itListensOnTheIpv4LoopbackAndHandsTheAgentItsPortAndToken() {
        assertTrue(listener.port() > 0);
        assertTrue(listener.token().matches("[0-9a-f]{64}"));
        assertEquals(listener.jvmOptions(Path.of("nyajsw-agent.jar")), List.of(
                "-javaagent:" + Path.of("nyajsw-agent.jar"), "-Dnyajsw.agent.port=" + listener.port()));
        assertFalse(listener.channel().isPresent());
        assertEquals(listener.state(), AgentState.WAITING);
    }

    @Test(timeOut = 60_000)
    public void theAgentWithTheTokenBecomesTheSessionAndPingsAreAnswered() throws Exception {
        try (FakeAgent agent = FakeAgent.introduce(listener)) {
            AgentChannel channel = listener.channel().orElseThrow();

            assertEquals(channel.version(), AgentProtocol.VERSION);
            assertTrue(channel.ping(Duration.ofSeconds(10)));
            assertTrue(channel.ping(Duration.ofSeconds(10)));
            assertEquals(agent.received(), List.of("PING 1", "PING 2"));
            assertTrue(logged().contains("the agent of jvm 1 has connected (protocol 1)"), logged());
            assertFalse(logged().contains(listener.token()), "the token is never logged");
        }
    }

    @Test(timeOut = 60_000)
    public void aConnectionWithoutTheTokenIsRefusedOnceAsAWarningAndTheAgentCanStillCome() throws Exception {
        try (FakeAgent intruder = FakeAgent.connect(listener.port(), AgentProtocol.hello("0".repeat(64)))) {
            awaitLogged("refused a connection to the agent port of jvm 1: it did not introduce itself with the"
                    + " invocation's token (further refusals are logged at debug level)");
        }
        try (FakeAgent newer = FakeAgent.connect(listener.port(), "HELLO 2 " + listener.token());
             FakeAgent agent = FakeAgent.introduce(listener)) {
            assertTrue(listener.channel().orElseThrow().ping(Duration.ofSeconds(10)));
            assertFalse(logged().contains("protocol 2"), "a later refusal is not a warning: " + logged());
        }
    }

    @Test(timeOut = 60_000)
    public void aNewerAgentIsToldApart() throws Exception {
        try (FakeAgent newer = FakeAgent.connect(listener.port(), "HELLO 2 " + listener.token())) {
            awaitLogged("the agent speaks protocol 2, this wrapper 1");
        }
    }

    @Test(timeOut = 60_000)
    public void aConnectionThatSaysNothingOrDripsItsBytesCannotKeepTheAgentWaiting() throws Exception {
        try (Socket silent = new Socket(AgentProtocol.loopback(), listener.port());
             Socket dripping = new Socket(AgentProtocol.loopback(), listener.port())) {
            Thread drip = Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 0; i < 50; i++) {
                        dripping.getOutputStream().write('H');
                        Thread.sleep(100); // slower than a HELLO may take, as a whole line
                    }
                } catch (IOException | InterruptedException e) {
                    // refused: the listener closed it
                }
            });
            long start = System.nanoTime();
            try (FakeAgent agent = FakeAgent.introduce(listener)) {
                assertTrue(listener.channel().isPresent());
                assertTrue(System.nanoTime() - start < Duration.ofSeconds(4).toNanos(), "the agent was not held up");
            }
            drip.interrupt();
        }
    }

    @Test(timeOut = 60_000)
    public void aConnectionThatDoesNotIntroduceItselfInTimeIsRefused() throws Exception {
        try (Socket silent = new Socket(AgentProtocol.loopback(), listener.port())) {
            awaitLogged("refused a connection to the agent port of jvm 1: it did not introduce itself within 0.500 s");
            assertEquals(silent.getInputStream().read(), -1, "closed by the listener");
        }
    }

    @Test(timeOut = 60_000)
    public void withoutAnAgentTheListenerStopsAfterItsWindow() throws Exception {
        try (AgentListener waiting = AgentListener.open("jvm 2", log, new SecureRandom(), Duration.ofMillis(500),
                Duration.ofMillis(300))) {
            awaitLogged("the agent of jvm 2 did not connect within 0.300 s; the application runs without it");
            assertEquals(waiting.state(), AgentState.NONE, "it runs without the agent now");
            expectThrows(IOException.class, () -> new Socket(AgentProtocol.loopback(), waiting.port()).close());
        }
    }

    private void awaitLogged(String text) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!logged().contains(text)) {
            assertTrue(System.nanoTime() < deadline, "no '" + text + "' in " + logged());
            Thread.sleep(10); // the listener's threads log; nothing to wait on
        }
    }

    @Test(timeOut = 60_000)
    public void afterTheSessionNobodyElseCanConnect() throws Exception {
        try (FakeAgent agent = FakeAgent.introduce(listener)) {
            expectThrows(ConnectException.class, () -> new Socket(AgentProtocol.loopback(), listener.port()).close());
            expectThrows(IOException.class, () -> new Socket(InetAddress.getByName("::1"), listener.port()).close());
        }
    }

    @Test(timeOut = 60_000)
    public void anAnswerThatComesTooLateIsNotTakenForTheNextOne() throws Exception {
        try (FakeAgent agent = FakeAgent.introduce(listener)) {
            AgentChannel channel = listener.channel().orElseThrow();
            agent.holdAnswers();

            assertFalse(channel.ping(Duration.ofMillis(200)), "PING 1 is not answered in time");
            assertTrue(channel.isOpen(), "a slow answer does not end the session");
            agent.resume(); // PONG 1 comes now, then PONG 2
            assertTrue(channel.ping(Duration.ofSeconds(10)));
            assertEquals(agent.received(), List.of("PING 1", "PING 2"));
        }
    }

    @Test(timeOut = 60_000)
    public void aStopIsAcknowledged() throws Exception {
        try (FakeAgent agent = FakeAgent.introduce(listener)) {
            assertTrue(listener.channel().orElseThrow().stop(Duration.ofSeconds(10)));
            assertTrue(agent.awaitStop(10));
        }
    }

    @Test(timeOut = 60_000)
    public void aSessionTheAgentEndedIsGone() throws Exception {
        FakeAgent agent = FakeAgent.introduce(listener);
        AgentChannel channel = listener.channel().orElseThrow();
        assertEquals(listener.state(), AgentState.CONNECTED);

        agent.close();

        assertFalse(channel.ping(Duration.ofSeconds(10)));
        assertFalse(channel.isOpen());
        assertEquals(listener.state(), AgentState.LOST);
        assertFalse(listener.channel().isPresent());
        assertFalse(channel.stop(SECOND));
    }

    @Test(timeOut = 60_000)
    public void closingEndsTheSession() throws Exception {
        try (FakeAgent agent = FakeAgent.introduce(listener)) {
            AgentChannel channel = listener.channel().orElseThrow();

            listener.close();

            assertFalse(channel.isOpen());
            assertFalse(channel.ping(SECOND));
        }
    }
}
