package io.github.jacobosanchez.nyajsw.launch;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A tiny application the launcher tests start as a real child JVM.
 *
 * <ul>
 *   <li>{@code report <code>}: prints its arguments, working directory, the variables
 *       {@code NYAJSW_TEST}, {@code NYAJSW_REMOVED} and {@code NYAJSW_AGENT_TOKEN},
 *       whether standard input is at its end, and a
 *       line on standard error, then exits with {@code code};</li>
 *   <li>{@code cafe}: prints {@code caf\u00e9} on standard output and exits;</li>
 *   <li>{@code park}: prints {@code parked} and waits until it is killed;</li>
 *   <li>{@code serve <port> <ms>}: answers {@code /health} on {@code 127.0.0.1:<port>} with 503 for
 *       {@code ms}, then with 200, when it prints {@code serving}; waits until it ends, and prints
 *       {@code shutdown hook ran} from a shutdown hook;</li>
 *   <li>{@code options}: prints {@code options=} and its JVM's options, then waits until it is
 *       killed;</li>
 *   <li>{@code linger <ms> <code>}: prints {@code lingering}, waits {@code ms} and exits with
 *       {@code code}, like an application that finishes its own shutdown;</li>
 *   <li>{@code parent}: starts a {@code park} grandchild, prints {@code child <pid>} and waits until
 *       it is killed;</li>
 *   <li>{@code orphan}: starts a {@code park} grandchild that shares its output, prints
 *       {@code child <pid>} and exits, leaving the grandchild holding the pipes.</li>
 * </ul>
 */
public final class TestApplication {

    private TestApplication() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        switch (args[0]) {
            case "report" -> {
                System.out.println("args=" + List.of(args).subList(2, args.length));
                System.out.println("dir=" + Path.of("").toAbsolutePath());
                System.out.println("test=" + System.getenv("NYAJSW_TEST"));
                System.out.println("removed=" + System.getenv("NYAJSW_REMOVED"));
                System.out.println("token=" + System.getenv("NYAJSW_AGENT_TOKEN"));
                System.out.println("stdin-ended=" + (System.in.read() < 0));
                System.err.println("to stderr");
                System.exit(Integer.parseInt(args[1]));
            }
            case "linger" -> {
                System.out.println("lingering");
                Thread.sleep(Long.parseLong(args[1]));
                System.exit(Integer.parseInt(args[2]));
            }
            case "cafe" -> System.out.println("caf\u00e9");
            case "park" -> {
                System.out.println("parked");
                park();
            }
            case "serve" -> {
                // The port is taken at once, so nothing else can take it meanwhile; it answers 503 until ready.
                AtomicBoolean ready = new AtomicBoolean();
                HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),
                        Integer.parseInt(args[1])), 0);
                server.createContext("/health", exchange -> {
                    exchange.sendResponseHeaders(ready.get() ? 200 : 503, -1);
                    exchange.close();
                });
                server.start();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("shutdown hook ran")));
                Thread.sleep(Long.parseLong(args[2]));
                ready.set(true);
                System.out.println("serving");
                park();
            }
            case "options" -> {
                System.out.println("options=" + ManagementFactory.getRuntimeMXBean().getInputArguments());
                park();
            }
            case "parent", "orphan" -> {
                String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
                Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                        TestApplication.class.getName(), "park").inheritIO().start();
                System.out.println("child " + child.pid());
                if (args[0].equals("orphan")) {
                    System.exit(0);
                }
                park();
            }
            default -> throw new IllegalArgumentException(Objects.toString(args[0]));
        }
    }

    private static void park() throws InterruptedException {
        Object never = new Object();
        synchronized (never) {
            while (true) {
                never.wait();
            }
        }
    }
}
