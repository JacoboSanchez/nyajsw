package io.jsl.nyajsw.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * The channel between the wrapper and the agent in the application JVM (plan §4.7), defined once
 * for both ends. The wrapper listens on a loopback port it chooses at launch and passes it to the
 * application JVM as the system property {@link #PORT_PROPERTY}; it passes a random token as the
 * environment variable {@link #TOKEN_VARIABLE}, so that the token never appears on a command line.
 * The agent connects and introduces itself; then the wrapper asks and the agent answers:
 *
 * <pre>
 * agent   HELLO 1 &lt;token&gt;
 * wrapper PING &lt;n&gt;        agent PONG &lt;n&gt;
 * wrapper STOP            agent STOPPING, then System.exit(0)
 * </pre>
 *
 * <p>Every message is one line of ASCII ending with {@code \n}, at most {@link #MAX_LINE} bytes
 * before it; a line cut off by the end of the stream is no message. A {@code PING} may carry
 * anything after a blank, which its {@code PONG} repeats, so that a late answer is not taken for
 * the answer to the next ping. A message the agent does not know it answers with {@link #UNKNOWN},
 * and {@link #helloVersion} reads the version an agent speaks, so that a newer wrapper can tell an
 * older agent. Both ends use {@link #loopback()}, whatever either JVM prefers for IPv6. Java 8, no
 * dependencies: this class is loaded into the application JVM.
 */
public final class AgentProtocol {

    /** The system property that gives the agent the wrapper's port. */
    public static final String PORT_PROPERTY = "nyajsw.agent.port";

    /** The environment variable that gives the agent its token. */
    public static final String TOKEN_VARIABLE = "NYAJSW_AGENT_TOKEN";

    /** The version of the protocol, sent with {@link #HELLO}. */
    public static final int VERSION = 1;

    /** The agent introduces itself: {@code HELLO <version> <token>}. */
    public static final String HELLO = "HELLO";

    /** The wrapper asks whether the application JVM still answers. */
    public static final String PING = "PING";

    /** The answer to {@link #PING}. */
    public static final String PONG = "PONG";

    /** The wrapper asks the application JVM to end with {@code System.exit(0)}, running its shutdown hooks. */
    public static final String STOP = "STOP";

    /** The answer to {@link #STOP}, sent before the agent calls {@code System.exit}. */
    public static final String STOPPING = "STOPPING";

    /** The answer to a message the agent does not know. */
    public static final String UNKNOWN = "UNKNOWN";

    /** The longest line, without its {@code \n}. */
    public static final int MAX_LINE = 256;

    /** Bytes of randomness in a token. */
    private static final int TOKEN_BYTES = 32;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private AgentProtocol() {
    }

    /**
     * The address both ends use: {@code 127.0.0.1}, not {@link InetAddress#getLoopbackAddress()},
     * which is {@code ::1} in a JVM that prefers IPv6 addresses; a wrapper and an agent that
     * disagreed would not meet, and another local user could take the port the wrapper does not hold.
     */
    public static InetAddress loopback() {
        try {
            return InetAddress.getByAddress("localhost", new byte[] {127, 0, 0, 1});
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e); // only for an address of the wrong length
        }
    }

    /** A new random token: 64 hexadecimal digits. */
    public static String newToken(SecureRandom random) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        char[] text = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            text[2 * i] = HEX[(bytes[i] >> 4) & 0xf];
            text[2 * i + 1] = HEX[bytes[i] & 0xf];
        }
        return new String(text);
    }

    /** The agent's first line. */
    public static String hello(String token) {
        return HELLO + " " + VERSION + " " + token;
    }

    /** The version a {@link #HELLO} line speaks, or -1 for a line that is no {@code HELLO <version> <token>}. */
    public static int helloVersion(String line) {
        if (line == null || !line.startsWith(HELLO + " ")) {
            return -1;
        }
        int end = line.indexOf(' ', HELLO.length() + 1);
        if (end < 0) {
            return -1;
        }
        try {
            int version = Integer.parseInt(line.substring(HELLO.length() + 1, end));
            return version > 0 ? version : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Whether {@code line} is a {@link #HELLO} of this version with {@code token}; the tokens are
     * compared in constant time.
     */
    public static boolean isHello(String line, String token) {
        if (helloVersion(line) != VERSION) {
            return false;
        }
        String given = line.substring(line.indexOf(' ', HELLO.length() + 1) + 1);
        return MessageDigest.isEqual(given.getBytes(StandardCharsets.US_ASCII),
                token.getBytes(StandardCharsets.US_ASCII));
    }

    /** A {@link #PING} carrying {@code id}. */
    public static String ping(long id) {
        return PING + " " + id;
    }

    /** The {@link #PONG} that answers {@code ping}: what came after its {@code PING}, repeated. */
    public static String pong(String ping) {
        return PONG + ping.substring(PING.length());
    }

    /** Whether {@code line} is a {@link #PING}, with or without something after a blank. */
    public static boolean isPing(String line) {
        return line.equals(PING) || line.startsWith(PING + " ");
    }

    /**
     * Reads one line, without its {@code \n} (and a {@code \r} before it). It reads a byte at a
     * time, which suits a channel that carries a few lines a minute.
     *
     * @return the line, or {@code null} at the end of the stream, also when a line was cut off by it
     * @throws IOException when the line is longer than {@link #MAX_LINE}, or reading fails
     */
    public static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') {
                byte[] bytes = line.toByteArray();
                int length = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
                return new String(bytes, 0, length, StandardCharsets.US_ASCII);
            }
            if (line.size() >= MAX_LINE && !(line.size() == MAX_LINE && b == '\r')) {
                throw new IOException("a line of the agent channel is longer than " + MAX_LINE + " bytes");
            }
            line.write(b);
        }
        return null;
    }

    /** Writes {@code line} and its {@code \n}, and flushes. */
    public static void writeLine(OutputStream out, String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }
}
