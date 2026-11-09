package io.jsl.nyajsw.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * The agent the wrapper loads into the application JVM with {@code -javaagent} (plan §4.7): it
 * connects back to the wrapper over loopback ({@link AgentProtocol}), answers its pings and, when
 * the wrapper asks it to stop, ends the JVM with {@code System.exit(0)}, so that the application's
 * shutdown hooks run as they would on Ctrl+C.
 *
 * <p>It never holds up the application: {@code premain} only starts a daemon thread and returns,
 * and whatever goes wrong (no wrapper to reach, a security manager that forbids what it needs, a
 * broken connection) costs one line on standard error and the agent, never the application. It
 * changes nothing else in the JVM and uses only the JDK (Java 8).
 */
public final class Agent {

    /** The name of the agent's thread. */
    static final String THREAD = "nyajsw-agent";

    /** How often the agent tries to reach the wrapper, and how long it waits between attempts. */
    static final int CONNECT_ATTEMPTS = 20;
    static final long CONNECT_PAUSE_MILLIS = 250;
    static final int CONNECT_TIMEOUT_MILLIS = 2000;

    /** Ends the JVM; {@code System.exit} outside tests. */
    interface Exit {
        void exit(int code);
    }

    private final int port;
    private final String token;
    private final Exit exit;
    private final PrintStream messages;
    private final int attempts;
    private final long pauseMillis;

    /**
     * @param messages where the agent says what went wrong: standard error outside tests
     */
    Agent(int port, String token, Exit exit, PrintStream messages, int attempts, long pauseMillis) {
        this.port = port;
        this.token = token;
        this.exit = exit;
        this.messages = messages;
        this.attempts = attempts;
        this.pauseMillis = pauseMillis;
    }

    /** The entry point of {@code -javaagent}. */
    public static void premain(String arguments, Instrumentation instrumentation) {
        premain(arguments);
    }

    /** The entry point of {@code -javaagent} for JVMs that call the form without instrumentation. */
    public static void premain(String arguments) {
        try {
            start(System.getProperty(AgentProtocol.PORT_PROPERTY), System.getenv(AgentProtocol.TOKEN_VARIABLE),
                    new Exit() {
                        @Override
                        public void exit(int code) {
                            System.exit(code); // the agent's whole purpose on STOP (AGENTS.md §4.4)
                        }
                    }, System.err);
        } catch (RuntimeException e) {
            // A security manager that forbids reading the property or the variable, say: an exception
            // out of premain would keep the application from starting at all.
            System.err.println("nyajsw agent: disabled (" + e.getClass().getName() + "); the application runs"
                    + " without it");
        }
    }

    /**
     * Starts the agent's thread, unless the port or the token is missing or the port is not a
     * number, in which case there is no wrapper to talk to and a line in {@code messages} says so.
     *
     * @return the thread, or {@code null} when nothing was started
     */
    static Thread start(String port, String token, Exit exit, PrintStream messages) {
        int number;
        try {
            number = port == null ? -1 : Integer.parseInt(port.trim());
        } catch (NumberFormatException e) {
            number = -1;
        }
        if (number < 1 || number > 65535 || token == null || token.isEmpty()) {
            messages.println("nyajsw agent: no wrapper to connect to (" + AgentProtocol.PORT_PROPERTY + " or "
                    + AgentProtocol.TOKEN_VARIABLE + " missing or invalid); the application runs without it");
            return null;
        }
        final Agent agent = new Agent(number, token, exit, messages, CONNECT_ATTEMPTS, CONNECT_PAUSE_MILLIS);
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                agent.run();
            }
        }, THREAD);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** Connects, introduces itself and answers the wrapper until the connection ends. */
    void run() {
        Socket socket = null;
        try {
            socket = connect();
            if (socket == null) {
                messages.println("nyajsw agent: cannot reach the wrapper on port " + port
                        + "; the application runs without it");
                return;
            }
            serve(socket.getInputStream(), socket.getOutputStream());
        } catch (IOException e) {
            // The wrapper went away or broke the protocol; the application goes on without the agent.
        } catch (RuntimeException e) {
            // A security manager the application installed forbids the connection or the exit, say;
            // an uncaught exception could reach a handler of the application's that ends the JVM.
            messages.println("nyajsw agent: stopped (" + e.getClass().getName() + "); the application runs"
                    + " without it");
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    // nothing left to do
                }
            }
        }
    }

    /** Introduces itself on {@code out} and answers what comes on {@code in}, until it ends or asks for a stop. */
    void serve(InputStream in, OutputStream out) throws IOException {
        AgentProtocol.writeLine(out, AgentProtocol.hello(token));
        String line;
        while ((line = AgentProtocol.readLine(in)) != null) {
            if (AgentProtocol.isPing(line)) {
                AgentProtocol.writeLine(out, AgentProtocol.pong(line));
            } else if (line.equals(AgentProtocol.STOP)) {
                try {
                    AgentProtocol.writeLine(out, AgentProtocol.STOPPING);
                } catch (IOException e) {
                    // The wrapper asked and may have gone already; the stop is what it wanted.
                }
                exit.exit(0);
                return;
            } else {
                AgentProtocol.writeLine(out, AgentProtocol.UNKNOWN);
            }
        }
    }

    /** The connection to the wrapper, or {@code null} once every attempt has failed. */
    private Socket connect() {
        for (int attempt = 1; attempt <= attempts; attempt++) {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(AgentProtocol.loopback(), port), CONNECT_TIMEOUT_MILLIS);
                socket.setTcpNoDelay(true);
                return socket;
            } catch (IOException e) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // not connected anyway
                }
            }
            try {
                Thread.sleep(pauseMillis); // the wrapper listens before the launch; this is for a busy machine
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }
}
