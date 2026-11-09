package io.jsl.nyajsw.launch;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A tiny application the launcher tests start as a real child JVM.
 *
 * <ul>
 *   <li>{@code report <code>}: prints its arguments, working directory, the variables
 *       {@code NYAJSW_TEST}, {@code NYAJSW_REMOVED} and {@code NYAJSW_AGENT_TOKEN},
 *       whether standard input is at its end, and a
 *       line on standard error, then exits with {@code code};</li>
 *   <li>{@code pid <file> <mode> <arguments>}: writes its process id to {@code file}, which appears
 *       whole, then runs as {@code mode} would, so a test can find its own process;</li>
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

    /** The {@code java} executable of the JVM running the tests. */
    public static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** The command line that runs this application with {@code arguments}, in any working directory. */
    public static List<String> command(String... arguments) {
        String classes;
        try {
            classes = Path.of(TestApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
        List<String> command = new ArrayList<>(List.of(java(), "-cp", classes, TestApplication.class.getName()));
        command.addAll(List.of(arguments));
        return command;
    }

    /**
     * The process that ran {@code pid <file> ...}, once {@code file} has appeared: at most 60 s, since
     * nothing tells when a JVM has started.
     */
    public static ProcessHandle awaitPid(Path file) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!Files.exists(file)) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("no " + file + " after 60 s");
            }
            Thread.sleep(20);
        }
        long pid = Long.parseLong(Files.readString(file).strip());
        return ProcessHandle.of(pid).orElseThrow(() -> new IllegalStateException("process " + pid + " has ended"));
    }

    /**
     * Kills, with what they started, the processes whose pid files ({@code *.pid}) are in {@code dir},
     * and waits until they have ended: test clean-up that touches no other test's processes.
     */
    public static void killAll(Path dir) throws IOException, InterruptedException {
        List<Path> files;
        try (var listing = Files.list(dir)) {
            files = listing.filter(file -> file.getFileName().toString().endsWith(".pid")).toList();
        }
        for (Path file : files) {
            Optional<ProcessHandle> process = ProcessHandle.of(Long.parseLong(Files.readString(file).strip()));
            if (process.isPresent()) {
                List<ProcessHandle> tree = new ArrayList<>(process.get().descendants().toList());
                tree.add(process.get());
                // Not ProcessTrees: this class also runs in child JVMs, whose classpath has only the test classes.
                tree.forEach(ProcessHandle::destroyForcibly);
                for (ProcessHandle left : tree) {
                    try {
                        left.onExit().get(30, TimeUnit.SECONDS);
                    } catch (ExecutionException | TimeoutException e) {
                        throw new IllegalStateException("process " + left.pid() + " did not end", e);
                    }
                }
            }
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        switch (args[0]) {
            case "pid" -> {
                Path file = Path.of(args[1]);
                Path partial = file.resolveSibling(file.getFileName() + ".partial");
                Files.writeString(partial, Long.toString(ProcessHandle.current().pid()));
                Files.move(partial, file, StandardCopyOption.ATOMIC_MOVE);
                main(Arrays.copyOfRange(args, 2, args.length));
            }
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
