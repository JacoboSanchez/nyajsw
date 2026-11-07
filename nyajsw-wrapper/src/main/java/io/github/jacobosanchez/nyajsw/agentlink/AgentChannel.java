package io.github.jacobosanchez.nyajsw.agentlink;

import io.github.jacobosanchez.nyajsw.agent.AgentProtocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The wrapper's end of one invocation's session with its agent ({@link AgentProtocol}), once the
 * agent has introduced itself with the invocation's token. One exchange runs at a time: a ping from
 * a liveness check and a stop from a stop strategy wait for each other, the wait counted against
 * each one's own timeout (a lock, not a monitor, so that a waiting virtual thread does not pin its
 * carrier). A ping answered too late is not taken for the answer to the next one: each carries its
 * own number. Thread-safe.
 */
public final class AgentChannel implements AutoCloseable {

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final int version;
    private final ReentrantLock exchanging = new ReentrantLock();
    /** Guarded by {@link #exchanging}. */
    private long pings;
    private volatile boolean closed;

    /**
     * @param socket  the accepted connection, its {@code HELLO} read
     * @param version the protocol version the agent speaks
     */
    AgentChannel(Socket socket, int version) throws IOException {
        this.socket = Objects.requireNonNull(socket, "socket");
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
        this.version = version;
    }

    /** The protocol version the agent speaks. */
    public int version() {
        return version;
    }

    /** Whether the session is still open: neither end has closed it, and no exchange has failed. */
    public boolean isOpen() {
        return !closed;
    }

    /**
     * Pings the agent.
     *
     * @return whether it answered within {@code timeout}; a session that broke is closed
     */
    public boolean ping(Duration timeout) {
        return exchange(null, timeout);
    }

    /**
     * Asks the agent to end the application JVM; it calls {@code System.exit(0)} after its answer.
     *
     * @return whether it acknowledged the request within {@code timeout}
     */
    public boolean stop(Duration timeout) {
        return exchange(AgentProtocol.STOP, timeout);
    }

    /**
     * Sends {@code request} ({@code null}: the next ping) and reads until its answer, all within
     * {@code timeout}, the wait for another exchange included; a timeout keeps the session, a
     * broken one closes it.
     */
    private boolean exchange(String request, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            if (closed || !exchanging.tryLock(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        try {
            String sent = request != null ? request : AgentProtocol.ping(++pings);
            String answer = request != null ? AgentProtocol.STOPPING : AgentProtocol.pong(sent);
            return send(sent, answer, deadline);
        } finally {
            exchanging.unlock();
        }
    }

    private boolean send(String request, String answer, long deadline) {
        try {
            AgentProtocol.writeLine(out, request);
            while (true) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                socket.setSoTimeout((int) Math.max(1, Math.min(Integer.MAX_VALUE, left / 1_000_000)));
                String line = AgentProtocol.readLine(in);
                if (line == null) {
                    close();
                    return false;
                }
                if (line.equals(answer)) {
                    return true;
                }
                // Anything else is the late answer to an earlier ping: skipped.
            }
        } catch (SocketTimeoutException e) {
            return false;
        } catch (IOException e) {
            close();
            return false;
        }
    }

    @Override
    public void close() {
        closed = true;
        AgentListener.quietly(socket);
    }
}
