package io.github.jacobosanchez.nyajsw.cli;

import io.github.jacobosanchez.nyajsw.config.ConfigException;
import io.github.jacobosanchez.nyajsw.config.ConfigLoader;
import io.github.jacobosanchez.nyajsw.config.DebugDetail;
import io.github.jacobosanchez.nyajsw.config.LogLevel;
import io.github.jacobosanchez.nyajsw.config.WrapperConfig;
import io.github.jacobosanchez.nyajsw.launch.ClasspathExpander;
import io.github.jacobosanchez.nyajsw.launch.JavaCommandBuilder;
import io.github.jacobosanchez.nyajsw.log.WrapperLog;

import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * The command-line entry point (plan §3.2, §4.9) and the only place in the wrapper that calls
 * {@code System.exit} (and, in the console shutdown hook, {@code Runtime.halt}, which sets the exit
 * code once the JVM is already shutting down). This phase implements console mode ({@code -c}); the
 * service commands follow in P5 and P6 and are recognised already, so that a YAJSW runbook gets a
 * clear answer instead of a usage error.
 */
public final class Main {

    private static final Set<String> CONSOLE = Set.of("-c", "--console");
    private static final Set<String> NOT_YET = Set.of("-i", "--install", "-r", "--remove", "-t", "--start", "-p",
            "--stop", "-q", "--query", "--service");

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out));
    }

    /**
     * Runs one command and returns the process exit code: the application's in console mode, an
     * {@link ExitCode} otherwise.
     */
    static int run(String[] args, PrintStream out) {
        List<String> arguments = List.of(args);
        if (arguments.isEmpty()) {
            out.println(usage());
            return ExitCode.UNEXPECTED_ERROR.code();
        }
        String command = arguments.get(0);
        if (command.equals("--help") || command.equals("-h")) {
            out.println(usage());
            return ExitCode.SUCCESS.code();
        }
        if (command.equals("--version")) {
            out.println("nyajsw " + version());
            return ExitCode.SUCCESS.code();
        }
        if (NOT_YET.contains(command)) {
            out.println(command + " is not implemented yet in this version of nyajsw; only -c (console) is");
            return ExitCode.UNEXPECTED_ERROR.code();
        }
        if (!CONSOLE.contains(command) || arguments.size() != 2) {
            out.println(usage());
            return ExitCode.UNEXPECTED_ERROR.code();
        }
        return console(arguments.get(1), out);
    }

    private static int console(String file, PrintStream out) {
        WrapperConfig config;
        try (WrapperLog bootstrap = WrapperLog.console(out, LogLevel.INFO)) {
            try {
                config = WrapperConfig.from(ConfigLoader.forCurrentProcess().load(Path.of(file)));
            } catch (ConfigException e) {
                e.problems().forEach(problem -> bootstrap.error(problem.getMessage()));
                return ExitCode.CONFIGURATION_ERROR.code();
            } catch (InvalidPathException e) {
                bootstrap.error("not a valid path for the configuration file: " + file);
                return ExitCode.CONFIGURATION_ERROR.code();
            }
        }
        try (WrapperLog log = WrapperLog.open(config.log(), out)) {
            ConsoleMode mode;
            try {
                mode = new ConsoleMode(config, log, JavaCommandBuilder.forThisJvm(), ClasspathExpander.forThisHost());
            } catch (RuntimeException e) {
                return unexpected(e, config, log);
            }
            Thread hook = Thread.ofPlatform().name("nyajsw-console-stop")
                    .unstarted(consoleStopHook(mode, out, Runtime.getRuntime()::halt));
            Runtime.getRuntime().addShutdownHook(hook);
            try {
                return mode.run();
            } catch (RuntimeException e) {
                mode.stop();
                return unexpected(e, config, log);
            } finally {
                try {
                    // A Ctrl+C between here and System.exit ends the JVM with the signal's code: a window
                    // of a few instructions, after the application has ended anyway.
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException e) {
                    // Shutting down already: the hook is running or has run.
                }
            }
        }
    }

    /**
     * What runs on Ctrl+C or the end of the session (D21): stop through the supervisor, wait for it
     * (bounded, {@link ConsoleMode#stop()}), flush the console, then end the JVM with the
     * application's exit code. Once a signal has begun the shutdown the exit code is the signal's,
     * and {@code System.exit} would block; {@code halt} is the only way to set it, at the price of
     * the hooks that have not run yet, which nyajsw does not rely on. A Ctrl+C before {@code run()}
     * has begun starts nothing and exits with 0.
     */
    static Runnable consoleStopHook(ConsoleMode mode, PrintStream out, IntConsumer halt) {
        return () -> {
            int code = mode.stop();
            out.flush();
            halt.accept(code);
        };
    }

    private static int unexpected(RuntimeException e, WrapperConfig config, WrapperLog log) {
        log.fatal("unexpected error: " + config.secrets().maskText(String.valueOf(e)));
        log.debug(DebugDetail.APPLICATION, () -> config.secrets().maskText(stackTrace(e)));
        return ExitCode.UNEXPECTED_ERROR.code();
    }

    private static String stackTrace(Throwable e) {
        java.io.StringWriter trace = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(trace));
        return trace.toString();
    }

    private static String version() {
        String version = Main.class.getPackage().getImplementationVersion();
        return version == null ? "(development build)" : version;
    }

    private static String usage() {
        return String.join(System.lineSeparator(),
                "usage: nyajsw -c <wrapper.conf>    run the application in the console",
                "       nyajsw --help | --version",
                "The service commands -i -r -t -p -q follow in a later version.");
    }
}
