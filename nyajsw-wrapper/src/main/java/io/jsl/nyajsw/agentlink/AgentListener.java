package io.jsl.nyajsw.agentlink;

import io.jsl.nyajsw.agent.AgentProtocol;
import io.jsl.nyajsw.log.Durations;
import io.jsl.nyajsw.log.WrapperLog;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Waits for one invocation's agent (plan §4.7). It is opened before the launch: it binds a port of
 * its own on {@code 127.0.0.1} ({@link AgentProtocol#loopback()}), exclusively (the JDK's default
 * on Windows, {@code sun.net.useExclusiveBind}; address reuse is off elsewhere), so that the agent
 * cannot meet anybody else there, and draws a new token. The first connection that introduces
 * itself with that token becomes the invocation's {@link AgentChannel}, and the listener stops
 * listening, so that no second session can follow: the agent's handshake is one-way (it sends its
 * token to whoever accepted), and the processes the application starts inherit the token.
 *
 * <p>Other local users can connect too, so every connection is introduced on a thread of its own
 * and must complete its {@code HELLO} line within {@link #HELLO_TIMEOUT}, the whole line, not each
 * byte: one that is slow or silent cannot keep the agent waiting behind it. The first refusal is
 * a warning, later ones are logged at debug level only. Without an agent after {@link
 * #CONNECT_WINDOW} (the agent's own attempts are long over by then) the listener stops with a
 * warning, so that the port and the token do not stay open for the whole run.
 *
 * <p>Thread-safe.
 */
public final class AgentListener implements AutoCloseable {

    /** How long a connection may take to introduce itself; the agent does it right after connecting. */
    public static final Duration HELLO_TIMEOUT = Duration.ofSeconds(2);

    /** How long the listener waits for the agent at all. */
    public static final Duration CONNECT_WINDOW = Duration.ofSeconds(60);

    /** Connections that may wait to be accepted. */
    private static final int BACKLOG = 8;

    /** Introductions that may run at once; more are refused at once rather than queued. */
    private static final int MAX_PENDING = 16;

    private final ServerSocket server;
    private final String token;
    private final String name;
    private final WrapperLog log;
    private final Duration helloTimeout;
    private final AtomicReference<AgentChannel> channel = new AtomicReference<>();
    private final Set<Socket> pending = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean refusedOnce = new AtomicBoolean();
    private volatile boolean closed;
    /** Set when the window ran out without an agent. */
    private volatile boolean gaveUp;

    private AgentListener(ServerSocket server, String token, String name, WrapperLog log, Duration helloTimeout) {
        this.server = server;
        this.token = token;
        this.name = name;
        this.log = log;
        this.helloTimeout = helloTimeout;
    }

    /**
     * Binds a new port and starts accepting.
     *
     * @param name the invocation, {@code jvm N}, for log messages
     * @throws IOException when no port can be bound
     */
    public static AgentListener open(String name, WrapperLog log, SecureRandom random) throws IOException {
        return open(name, log, random, HELLO_TIMEOUT, CONNECT_WINDOW);
    }

    /** As {@link #open(String, WrapperLog, SecureRandom)}, with other times, for tests. */
    static AgentListener open(String name, WrapperLog log, SecureRandom random, Duration helloTimeout,
            Duration connectWindow) throws IOException {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(log, "log");
        ServerSocket server = new ServerSocket();
        try {
            server.setReuseAddress(false);
            server.bind(new InetSocketAddress(AgentProtocol.loopback(), 0), BACKLOG);
        } catch (IOException e) {
            server.close();
            throw e;
        }
        AgentListener listener = new AgentListener(server, AgentProtocol.newToken(random), name, log, helloTimeout);
        String thread = "nyajsw-" + name.replace(' ', '-') + "-agent";
        Thread.ofPlatform().daemon().name(thread).start(listener::accept);
        Thread.ofVirtual().name(thread + "-window").start(() -> listener.closeAfter(connectWindow));
        return listener;
    }

    /** The port the agent is to connect to. */
    public int port() {
        return server.getLocalPort();
    }

    /**
     * The JVM options that load the agent from {@code jar} and tell it this port; the token goes
     * into the environment instead ({@link #token()}).
     */
    public List<String> jvmOptions(Path jar) {
        return List.of("-javaagent:" + jar, "-D" + AgentProtocol.PORT_PROPERTY + "=" + port());
    }

    /** The token the agent is to introduce itself with; it goes into the environment, never into a log. */
    public String token() {
        return token;
    }

    /** The agent's session, once it has introduced itself and while it is open. */
    public Optional<AgentChannel> channel() {
        return Optional.ofNullable(channel.get()).filter(AgentChannel::isOpen);
    }

    /** Where the invocation stands with its agent. */
    public AgentState state() {
        AgentChannel session = channel.get();
        if (session != null) {
            return session.isOpen() ? AgentState.CONNECTED : AgentState.LOST;
        }
        return closed || gaveUp ? AgentState.NONE : AgentState.WAITING;
    }

    /** Stops listening and ends the session, if any. */
    @Override
    public void close() {
        closed = true;
        stopListening();
        AgentChannel open = channel.get();
        if (open != null) {
            open.close();
        }
    }

    private void accept() {
        try {
            while (!closed && channel.get() == null) {
                Socket socket = server.accept();
                if (pending.size() >= MAX_PENDING) {
                    quietly(socket);
                    refused("too many connections are introducing themselves");
                    continue;
                }
                pending.add(socket);
                Thread.ofVirtual().name("nyajsw-agent-hello").start(() -> introduce(socket));
            }
        } catch (IOException e) {
            // closed: nothing more to accept
        } finally {
            stopListening();
        }
    }

    /** Makes {@code socket} the session if it introduces itself with the token in time; else closes it. */
    private void introduce(Socket socket) {
        Optional<AgentChannel> introduced = Optional.empty();
        try {
            introduced = hello(socket);
        } finally {
            pending.remove(socket);
            if (introduced.isEmpty()) {
                quietly(socket);
            }
        }
        if (introduced.isEmpty()) {
            return;
        }
        // One session per invocation: stop listening before anybody can see the session.
        stopListening();
        if (closed || !channel.compareAndSet(null, introduced.get())) {
            introduced.get().close(); // closed meanwhile, or another one was faster
            return;
        }
        log.status("the agent of " + name + " has connected (protocol " + introduced.get().version() + ")");
    }

    private Optional<AgentChannel> hello(Socket socket) {
        long deadline = System.nanoTime() + helloTimeout.toNanos();
        try {
            socket.setTcpNoDelay(true);
            String hello = readHello(socket, deadline);
            int version = AgentProtocol.helloVersion(hello);
            if (version == AgentProtocol.VERSION && AgentProtocol.isHello(hello, token)) {
                return Optional.of(new AgentChannel(socket, version));
            }
            refused(version > 0 && version != AgentProtocol.VERSION ? "the agent speaks protocol " + version
                    + ", this wrapper " + AgentProtocol.VERSION : "it did not introduce itself with the invocation's"
                    + " token");
        } catch (SocketTimeoutException e) {
            refused("it did not introduce itself within " + Durations.seconds(helloTimeout));
        } catch (IOException e) {
            // it went away, or broke the protocol
        }
        return Optional.empty();
    }

    /** The first line of {@code socket}, read before {@code deadline}, however slowly its bytes come. */
    private static String readHello(Socket socket, long deadline) throws IOException {
        InputStream in = socket.getInputStream();
        InputStream bounded = new InputStream() {
            @Override
            public int read() throws IOException {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw new SocketTimeoutException("hello deadline");
                }
                socket.setSoTimeout((int) Math.max(1, Math.min(Integer.MAX_VALUE, left / 1_000_000)));
                return in.read();
            }
        };
        return AgentProtocol.readLine(bounded);
    }

    /** Logs a refused connection: the first as a warning, later ones at debug level, so nobody can flood the log. */
    private void refused(String reason) {
        String message = "refused a connection to the agent port of " + name + ": " + reason;
        if (refusedOnce.compareAndSet(false, true)) {
            log.status("warning: " + message + " (further refusals are logged at debug level)");
        } else {
            log.debug(() -> message);
        }
    }

    private void closeAfter(Duration window) {
        try {
            Thread.sleep(window);
        } catch (InterruptedException e) {
            return;
        }
        if (!closed && channel.get() == null) {
            gaveUp = true;
            stopListening();
            if (channel.get() == null) { // an introduction that was under way may have won meanwhile
                log.status("warning: the agent of " + name + " did not connect within " + Durations.seconds(window)
                        + "; the application runs without it");
            }
        }
    }

    private void stopListening() {
        quietly(server);
        for (Socket socket : pending) {
            quietly(socket);
        }
    }

    /** Closes {@code resource}, which is closed whatever happens. */
    static void quietly(Closeable resource) {
        try {
            resource.close();
        } catch (IOException e) {
            // closed either way
        }
    }
}
