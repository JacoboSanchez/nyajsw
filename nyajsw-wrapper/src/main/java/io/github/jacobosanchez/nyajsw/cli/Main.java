package io.github.jacobosanchez.nyajsw.cli;

import io.github.jacobosanchez.nyajsw.config.ConfigException;
import io.github.jacobosanchez.nyajsw.config.ConfigLoader;
import io.github.jacobosanchez.nyajsw.config.LogConfig;
import io.github.jacobosanchez.nyajsw.config.LogLevel;
import io.github.jacobosanchez.nyajsw.config.WrapperConfig;
import io.github.jacobosanchez.nyajsw.launch.ClasspathExpander;
import io.github.jacobosanchez.nyajsw.launch.JavaCommandBuilder;
import io.github.jacobosanchez.nyajsw.launch.LaunchException;
import io.github.jacobosanchez.nyajsw.log.WrapperLog;

import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * The command-line entry point (plan §3.2, §4.9) and the only place in the wrapper that calls
 * {@code System.exit}. This phase implements console mode ({@code -c}); the service commands follow
 * in P5 and P6 and are recognised already, so that a YAJSW runbook gets a clear answer instead of a
 * usage error.
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
        try (WrapperLog log = WrapperLog.console(out, consoleLevel(config.log()))) {
            ConsoleMode mode = new ConsoleMode(config, log, JavaCommandBuilder.forThisJvm(),
                    ClasspathExpander.forThisHost());
            Thread hook = Thread.ofPlatform().name("nyajsw-console-stop").unstarted(mode::stop);
            Runtime.getRuntime().addShutdownHook(hook);
            try {
                return mode.run();
            } catch (LaunchException e) {
                log.fatal(e.getMessage());
                return ExitCode.UNEXPECTED_ERROR.code();
            } catch (RuntimeException e) {
                log.fatal("unexpected error: " + config.secrets().maskText(String.valueOf(e)));
                log.debug(() -> config.secrets().maskText(stackTrace(e)));
                mode.stop();
                return ExitCode.UNEXPECTED_ERROR.code();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                mode.stop();
                return ExitCode.UNEXPECTED_ERROR.code();
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException e) {
                    // Shutting down already: the hook is running or has run.
                }
            }
        }
    }

    /** {@code wrapper.console.loglevel}, raised to {@code DEBUG} by {@code wrapper.debug}. */
    static LogLevel consoleLevel(LogConfig log) {
        // The registry gives wrapper.console.loglevel a default, so it is always there.
        return log.debug() ? LogLevel.DEBUG : log.consoleLevel().orElseThrow();
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
