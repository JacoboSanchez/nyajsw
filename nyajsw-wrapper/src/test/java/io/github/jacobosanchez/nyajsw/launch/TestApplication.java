package io.github.jacobosanchez.nyajsw.launch;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * A tiny application the launcher tests start as a real child JVM.
 *
 * <ul>
 *   <li>{@code report <code>}: prints its arguments, working directory, the variables
 *       {@code NYAJSW_TEST} and {@code NYAJSW_REMOVED}, whether standard input is at its end, and a
 *       line on standard error, then exits with {@code code};</li>
 *   <li>{@code cafe}: prints {@code caf\u00e9} on standard output and exits;</li>
 *   <li>{@code park}: prints {@code parked} and waits until it is killed;</li>
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
