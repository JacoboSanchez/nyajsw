package io.jsl.nyajsw.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import org.testng.annotations.Test;

public class AgentProtocolTest {

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    public void tokensAreRandomHexadecimal() {
        SecureRandom random = new SecureRandom();
        String token = AgentProtocol.newToken(random);

        assertTrue(token.matches("[0-9a-f]{64}"), token);
        assertNotEquals(AgentProtocol.newToken(random), token);
    }

    @Test
    public void helloCarriesTheVersionAndTheToken() {
        String hello = AgentProtocol.hello("abc");

        assertEquals(hello, "HELLO 1 abc");
        assertTrue(AgentProtocol.isHello(hello, "abc"));
        assertFalse(AgentProtocol.isHello(hello, "abd"));
        assertFalse(AgentProtocol.isHello(hello, "ab"));
        assertFalse(AgentProtocol.isHello("HELLO 2 abc", "abc"), "another version");
        assertFalse(AgentProtocol.isHello("PING", "abc"));
        assertFalse(AgentProtocol.isHello(null, "abc"));
    }

    @Test
    public void theVersionOfAHelloCanBeReadApartFromItsToken() {
        assertEquals(AgentProtocol.helloVersion("HELLO 1 abc"), 1);
        assertEquals(AgentProtocol.helloVersion("HELLO 7 abc"), 7, "a newer agent");
        assertEquals(AgentProtocol.helloVersion("HELLO x abc"), -1);
        assertEquals(AgentProtocol.helloVersion("HELLO 0 abc"), -1);
        assertEquals(AgentProtocol.helloVersion("HELLO 1"), -1, "no token");
        assertEquals(AgentProtocol.helloVersion("PONG"), -1);
        assertEquals(AgentProtocol.helloVersion(null), -1);
    }

    @Test
    public void aPongRepeatsWhatItsPingCarried() {
        assertEquals(AgentProtocol.ping(42), "PING 42");
        assertTrue(AgentProtocol.isPing("PING 42"));
        assertTrue(AgentProtocol.isPing("PING"));
        assertFalse(AgentProtocol.isPing("PINGS"));
        assertEquals(AgentProtocol.pong("PING 42"), "PONG 42");
        assertEquals(AgentProtocol.pong("PING"), "PONG");
    }

    @Test
    public void bothEndsMeetOnTheIpv4Loopback() {
        assertEquals(AgentProtocol.loopback().getHostAddress(), "127.0.0.1");
        assertTrue(AgentProtocol.loopback().isLoopbackAddress());
    }

    @Test
    public void linesEndWithANewlineAndAnOptionalCarriageReturn() throws IOException {
        InputStream in = stream("PING\r\nSTOP\nlast");

        assertEquals(AgentProtocol.readLine(in), "PING");
        assertEquals(AgentProtocol.readLine(in), "STOP");
        assertNull(AgentProtocol.readLine(in), "a last line cut off by the end is no message");
        assertNull(AgentProtocol.readLine(in), "the end of the stream");
    }

    @Test
    public void aLineLongerThanTheLimitIsAnError() throws IOException {
        assertEquals(AgentProtocol.readLine(stream("x".repeat(AgentProtocol.MAX_LINE) + "\n")).length(),
                AgentProtocol.MAX_LINE);
        assertEquals(AgentProtocol.readLine(stream("x".repeat(AgentProtocol.MAX_LINE) + "\r\n")).length(),
                AgentProtocol.MAX_LINE, "the carriage return does not count");
        expectThrows(IOException.class,
                () -> AgentProtocol.readLine(stream("x".repeat(AgentProtocol.MAX_LINE + 1) + "\n")));
        expectThrows(IOException.class,
                () -> AgentProtocol.readLine(stream("x".repeat(AgentProtocol.MAX_LINE) + "\ry\n")));
    }

    @Test
    public void writtenLinesEndWithANewline() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        AgentProtocol.writeLine(out, AgentProtocol.PONG);

        assertEquals(out.toString(StandardCharsets.US_ASCII), "PONG\n");
    }
}
