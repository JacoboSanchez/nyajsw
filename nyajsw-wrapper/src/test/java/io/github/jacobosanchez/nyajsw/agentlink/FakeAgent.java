package io.github.jacobosanchez.nyajsw.agentlink;

import io.github.jacobosanchez.nyajsw.agent.AgentProtocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * An agent for the wrapper's side of the channel, written against {@link AgentProtocol} as the real
 * one is: it connects to a listener, introduces itself and answers on a thread of its own. It can
 * hold back its answers, to make the wrapper wait; it records what it was sent.
 */
public final class FakeAgent implements AutoCloseable {

    private final Socket socket;
    private final List<String> received = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch answering = new CountDownLatch(0);
    private final CountDownLatch stopped = new CountDownLatch(1);

    private FakeAgent(Socket socket) {
        this.socket = socket;
    }

    /** Connects to {@code port} and introduces itself with {@code hello}, then answers. */
    public static FakeAgent connect(int port, String hello) throws IOException {
        Socket socket = new Socket(AgentProtocol.loopback(), port);
        FakeAgent agent = new FakeAgent(socket);
        AgentProtocol.writeLine(socket.getOutputStream(), hello);
        Thread.ofPlatform().daemon().name("fake-agent").start(agent::answer);
        return agent;
    }

    /** Connects to {@code listener} with its token and waits until the listener has the session. */
    public static FakeAgent introduce(AgentListener listener) throws IOException, InterruptedException {
        FakeAgent agent = connect(listener.port(), AgentProtocol.hello(listener.token()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (listener.channel().isEmpty()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("the listener did not take the session");
            }
            Thread.sleep(10); // the listener's thread takes it; nothing to wait on
        }
        return agent;
    }

    /** Holds back the answers until {@link #resume()}. */
    public void holdAnswers() {
        answering = new CountDownLatch(1);
    }

    /** Lets the held answers go. */
    public void resume() {
        answering.countDown();
    }

    /** What the wrapper sent, in order. */
    public List<String> received() {
        return received;
    }

    /** Waits until the wrapper has sent {@code STOP}. */
    public boolean awaitStop(long seconds) throws InterruptedException {
        return stopped.await(seconds, TimeUnit.SECONDS);
    }

    private void answer() {
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            String line;
            while ((line = AgentProtocol.readLine(in)) != null) {
                received.add(line);
                answering.await();
                if (AgentProtocol.isPing(line)) {
                    AgentProtocol.writeLine(out, AgentProtocol.pong(line));
                } else if (line.equals(AgentProtocol.STOP)) {
                    AgentProtocol.writeLine(out, AgentProtocol.STOPPING);
                    stopped.countDown();
                }
            }
        } catch (IOException e) {
            // closed
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() throws IOException {
        answering.countDown();
        socket.close();
    }
}
