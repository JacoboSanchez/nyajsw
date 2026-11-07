package io.github.jacobosanchez.nyajsw.cli;

import io.github.jacobosanchez.nyajsw.NyajswException;
import io.github.jacobosanchez.nyajsw.config.ConfigException;
import io.github.jacobosanchez.nyajsw.config.ConfigLoader;
import io.github.jacobosanchez.nyajsw.config.DebugDetail;
import io.github.jacobosanchez.nyajsw.config.LogLevel;
import io.github.jacobosanchez.nyajsw.config.WrapperConfig;
import io.github.jacobosanchez.nyajsw.launch.ClasspathExpander;
import io.github.jacobosanchez.nyajsw.launch.JavaCommandBuilder;
import io.github.jacobosanchez.nyajsw.log.WrapperLog;
import io.github.jacobosanchez.nyajsw.platform.Platform;
import io.github.jacobosanchez.nyajsw.platform.Platforms;

import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * The command-line entry point (plan §3.2, §4.9) and the only place in the wrapper that calls
 * {@code System.exit} (and, in the console shutdown hook, {@code Runtime.halt}, which sets the exit
 * code once the JVM is already shutting down). It runs console mode ({@code -c}), the service
 * commands ({@code -i -r -t -p -q}, {@link ServiceCommands}) and service mode ({@code --service},
 * {@link ServiceMode}), the command line the Service Control Manager runs.
 */
public final class Main {

    private static final Set<String> CONSOLE = Set.of("-c", "--console");
    private static final String SERVICE = "--service";

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
        return run(args, out, Platforms::forThisHost);
    }

    /** {@link #run(String[], PrintStream)} on the platform {@code platform} gives, for tests. */
    static int run(String[] args, PrintStream out, Supplier<Platform> platform) {
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
        Optional<ServiceCommands.Action> action = ServiceCommands.Action.of(command);
        if (arguments.size() != 2 || (action.isEmpty() && !CONSOLE.contains(command) && !command.equals(SERVICE))) {
            out.println(usage());
            return ExitCode.UNEXPECTED_ERROR.code();
        }
        if (command.equals(SERVICE)) {
            return serviceMode(new ServiceMode(arguments.get(1), JavaCommandBuilder.forThisJvm(),
                    ClasspathExpander.forThisHost(), bootstrapFiles(arguments.get(1))), out, platform);
        }
        return action.isPresent() ? service(action.get(), arguments.get(1), out, platform)
                : console(arguments.get(1), out);
    }

    /**
     * Service mode: the process connects to the Service Control Manager before anything else (plan
     * §4.4), and its exit code is the service's result. What keeps it from running as a service at
     * all goes to the console, for someone who ran it by hand, and to the service's bootstrap log,
     * since a service has no console ({@link ServiceMode#bootstrapLog}).
     */
    static int serviceMode(ServiceMode mode, PrintStream out, Supplier<Platform> platform) {
        try {
            platform.get().serviceHost().run(mode);
            return mode.exitCode();
        } catch (NyajswException e) {
            report(e.getMessage(), mode, out);
            return ExitCode.of(e).code();
        } catch (RuntimeException | Error e) {
            report("unexpected error: " + e, mode, out);
            return ExitCode.UNEXPECTED_ERROR.code();
        }
    }

    /**
     * Where service mode's bootstrap log goes: beside the configuration file ({@link
     * WrapperLog#bootstrapFile(Path, String)}), or in the temporary directory when its path cannot
     * be parsed.
     */
    static Function<String, Path> bootstrapFiles(String file) {
        try {
            Path directory = Path.of(file).toAbsolutePath().getParent();
            if (directory != null) {
                return name -> WrapperLog.bootstrapFile(directory, name);
            }
        } catch (InvalidPathException e) {
            // the configuration cannot be read either; say so where a file can be written
        }
        return WrapperLog::bootstrapFile;
    }

    private static void report(String message, ServiceMode mode, PrintStream out) {
        String masked = mode.mask(message);
        try (WrapperLog console = WrapperLog.console(out, LogLevel.INFO)) {
            console.fatal(masked);
        }
        try (BootstrapLog bootstrap = mode.bootstrapLog()) {
            bootstrap.fatal(masked);
        }
    }

    /**
     * A service command, reported on the console only: it must not roll or write the log file of a
     * service that runs beside it.
     */
    private static int service(ServiceCommands.Action action, String file, PrintStream out,
            Supplier<Platform> platform) {
        try (WrapperLog log = WrapperLog.console(out, LogLevel.INFO)) {
            Optional<WrapperConfig> loaded = load(file, log::error);
            if (loaded.isEmpty()) {
                return ExitCode.CONFIGURATION_ERROR.code();
            }
            WrapperConfig config = loaded.get();
            try {
                new ServiceCommands(platform.get().serviceController(), log).run(action, config);
                return ExitCode.SUCCESS.code();
            } catch (ConfigException e) {
                e.problems().forEach(problem -> log.error(config.secrets().maskText(problem.getMessage())));
                return ExitCode.of(e).code();
            } catch (NyajswException e) {
                log.error(config.secrets().maskText(e.getMessage()));
                return ExitCode.of(e).code();
            } catch (RuntimeException e) {
                return unexpected(e, config, log);
            }
        }
    }

    /**
     * The configuration in {@code file}, or empty once {@code errors} has been told why it cannot be loaded.
     * Its {@code wrapper.tmp.path} becomes {@code jna.tmpdir} (unless the command line sets that)
     * before anything can load JNA, which unpacks its native library there (plan §4.4).
     */
    static Optional<WrapperConfig> load(String file, Consumer<String> errors) {
        try {
            WrapperConfig config = WrapperConfig.from(ConfigLoader.forCurrentProcess().load(Path.of(file)));
            useTmpPath(config, System.getProperties());
            return Optional.of(config);
        } catch (ConfigException e) {
            e.problems().forEach(problem -> errors.accept(problem.getMessage()));
        } catch (InvalidPathException e) {
            errors.accept("not a valid path for the configuration file: " + file);
        }
        return Optional.empty();
    }

    /** Sets {@code jna.tmpdir} in {@code properties} from {@code wrapper.tmp.path}, unless it is set. */
    static void useTmpPath(WrapperConfig config, java.util.Properties properties) {
        config.java().tmpPath().filter(tmp -> properties.getProperty("jna.tmpdir") == null)
                .ifPresent(tmp -> properties.setProperty("jna.tmpdir", tmp.toAbsolutePath().normalize().toString()));
    }

    private static int console(String file, PrintStream out) {
        WrapperConfig config;
        try (WrapperLog early = WrapperLog.console(out, LogLevel.INFO)) {
            Optional<WrapperConfig> loaded = load(file, early::error);
            if (loaded.isEmpty()) {
                return ExitCode.CONFIGURATION_ERROR.code();
            }
            config = loaded.get();
        }
        try (WrapperLog log = WrapperLog.open(config.log(), out, config.secrets())) {
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

    static int unexpected(RuntimeException e, WrapperConfig config, WrapperLog log) {
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
                "       nyajsw -i <wrapper.conf>    install the Windows service",
                "       nyajsw -r <wrapper.conf>    remove the service",
                "       nyajsw -t <wrapper.conf>    start the service and wait until it runs",
                "       nyajsw -p <wrapper.conf>    stop the service and wait until it has stopped",
                "       nyajsw -q <wrapper.conf>    show the service's state",
                "       nyajsw --service <wrapper.conf>  run as the service (the Service Control Manager runs this)",
                "       nyajsw --help | --version",
                "Long forms: --console --install --remove --start --stop --query.");
    }
}
