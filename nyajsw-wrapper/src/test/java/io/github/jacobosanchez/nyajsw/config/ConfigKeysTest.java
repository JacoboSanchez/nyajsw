package io.github.jacobosanchez.nyajsw.config;

import static io.github.jacobosanchez.nyajsw.config.KeyOrigin.NYAJSW;
import static io.github.jacobosanchez.nyajsw.config.KeyOrigin.YAJSW;
import static io.github.jacobosanchez.nyajsw.config.KeyStatus.IGNORED;
import static io.github.jacobosanchez.nyajsw.config.KeyStatus.PLANNED;
import static io.github.jacobosanchez.nyajsw.config.KeyStatus.SUPPORTED;
import static io.github.jacobosanchez.nyajsw.config.KeyStatus.UNSUPPORTED;
import static io.github.jacobosanchez.nyajsw.config.KeyStatus.UNSUPPORTED_FATAL;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class ConfigKeysTest {

    private static final KeyRegistry REGISTRY = ConfigKeys.registry();

    @Test
    public void everyConstantIsInTheRegistryExactlyOnce() throws IllegalAccessException {
        List<KeySpec> constants = new ArrayList<>();
        for (Field field : ConfigKeys.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (field.getType() == KeySpec.class && Modifier.isStatic(modifiers)) {
                assertTrue(Modifier.isPublic(modifiers) && Modifier.isFinal(modifiers), field.getName());
                constants.add((KeySpec) field.get(null));
            }
        }

        assertEquals(new HashSet<>(REGISTRY.specs()), new HashSet<>(constants));
        assertEquals(REGISTRY.specs().size(), constants.size());
    }

    @Test
    public void everyKeyIsInTheWrapperNamespace() {
        for (KeySpec spec : REGISTRY.specs()) {
            assertTrue(spec.name().startsWith("wrapper."), spec.name());
        }
    }

    @Test
    public void categoriesAreDeclaredInBlocks() {
        // Documentation is generated in registry order, so each category must be contiguous.
        Set<KeyCategory> finished = new HashSet<>();
        KeyCategory current = null;
        for (KeySpec spec : REGISTRY.specs()) {
            if (spec.category() != current) {
                assertTrue(finished.add(spec.category()), "category split: " + spec.category() + " at " + spec.name());
                current = spec.category();
            }
        }
    }

    /** Keys of the plan's initial compatibility matrix (§7, §4.7) with the status seeded from it. */
    @DataProvider
    public Object[][] seededKeys() {
        return new Object[][] {
                // Java process
                {"wrapper.java.command", PLANNED, YAJSW},
                {"wrapper.java.command.javaw", PLANNED, YAJSW},
                {"wrapper.java.command.minVersion", UNSUPPORTED, YAJSW},
                {"wrapper.java.command.maxVersion", UNSUPPORTED, YAJSW},
                {"wrapper.java.command.jreOnly", UNSUPPORTED, YAJSW},
                {"wrapper.java.command.jdkOnly", UNSUPPORTED, YAJSW},
                {"wrapper.java.command.preferJre", UNSUPPORTED, YAJSW},
                {"wrapper.java.command.preferJdk", UNSUPPORTED, YAJSW},
                {"wrapper.java.customProcName", UNSUPPORTED, YAJSW},
                {"wrapper.java.app.mainclass", PLANNED, YAJSW},
                {"wrapper.java.app.jar", PLANNED, YAJSW},
                {"wrapper.java.mainclass", IGNORED, YAJSW},
                {"wrapper.app.parameter.1", PLANNED, YAJSW},
                {"wrapper.java.classpath.12", PLANNED, YAJSW},
                {"wrapper.java.library.path.1", PLANNED, YAJSW},
                {"wrapper.java.additional.3", PLANNED, YAJSW},
                {"wrapper.java.initmemory", PLANNED, YAJSW},
                {"wrapper.java.maxmemory", PLANNED, YAJSW},
                {"wrapper.java.initmemory.relative", UNSUPPORTED, YAJSW},
                {"wrapper.java.maxmemory.relative", UNSUPPORTED, YAJSW},
                {"wrapper.app.env.PATH", PLANNED, YAJSW},
                {"wrapper.working.dir", PLANNED, YAJSW},
                {"wrapper.tmp.path", PLANNED, YAJSW},
                {"wrapper.image", PLANNED, YAJSW},
                {"wrapper.priority", UNSUPPORTED, YAJSW},
                {"wrapper.affinity", UNSUPPORTED, YAJSW},
                {"wrapper.app.account", UNSUPPORTED_FATAL, YAJSW},
                {"wrapper.app.password", UNSUPPORTED_FATAL, YAJSW},
                {"wrapper.java.monitor.deadlock", UNSUPPORTED, YAJSW},
                {"wrapper.groovy", UNSUPPORTED, YAJSW},
                // Lifecycle
                {"wrapper.control", PLANNED, YAJSW},
                {"wrapper.on_exit.0", PLANNED, YAJSW},
                {"wrapper.on_exit.-1", PLANNED, YAJSW},
                {"wrapper.on_exit.default", PLANNED, YAJSW},
                {"wrapper.restart.delay", PLANNED, YAJSW},
                {"wrapper.max_failed_invocations", PLANNED, YAJSW},
                {"wrapper.successful_invocation_time", PLANNED, YAJSW},
                {"wrapper.startup.timeout", PLANNED, YAJSW},
                {"wrapper.shutdown.timeout", PLANNED, YAJSW},
                {"wrapper.pidfile", PLANNED, YAJSW},
                {"wrapper.java.pidfile", PLANNED, YAJSW},
                {"wrapper.ping.interval", PLANNED, YAJSW},
                {"wrapper.ping.timeout", PLANNED, YAJSW},
                {"wrapper.restart.reload_configuration", UNSUPPORTED, YAJSW},
                {"wrapper.stop.conf", UNSUPPORTED, YAJSW},
                {"wrapper.exit_on_main_terminate", UNSUPPORTED, YAJSW},
                {"wrapper.exit_on_main_exception", UNSUPPORTED, YAJSW},
                {"wrapper.filter.trigger.1", UNSUPPORTED, YAJSW},
                {"wrapper.filter.script.1", UNSUPPORTED, YAJSW},
                {"wrapper.filter.script.1.args", UNSUPPORTED, YAJSW},
                {"wrapper.filter.action.1", PLANNED, YAJSW},
                {"wrapper.stop.strategy", PLANNED, NYAJSW},
                {"wrapper.stop.command", PLANNED, NYAJSW},
                // Logging
                {"wrapper.logfile", PLANNED, YAJSW},
                {"wrapper.logfile.format", PLANNED, YAJSW},
                {"wrapper.logfile.loglevel", PLANNED, YAJSW},
                {"wrapper.logfile.maxsize", PLANNED, YAJSW},
                {"wrapper.logfile.maxfiles", PLANNED, YAJSW},
                {"wrapper.logfile.rollmode", PLANNED, YAJSW},
                {"wrapper.console.format", PLANNED, YAJSW},
                {"wrapper.console.loglevel", PLANNED, YAJSW},
                {"wrapper.console.visible", UNSUPPORTED, YAJSW},
                {"wrapper.console.title", UNSUPPORTED, YAJSW},
                {"wrapper.console.pipestreams", IGNORED, YAJSW},
                {"wrapper.debug", PLANNED, YAJSW},
                {"wrapper.syslog.host", UNSUPPORTED, YAJSW},
                // Windows service
                {"wrapper.ntservice.name", PLANNED, YAJSW},
                {"wrapper.ntservice.displayname", PLANNED, YAJSW},
                {"wrapper.ntservice.description", PLANNED, YAJSW},
                {"wrapper.ntservice.starttype", PLANNED, YAJSW},
                {"wrapper.ntservice.dependency.1", PLANNED, YAJSW},
                {"wrapper.ntservice.account", PLANNED, YAJSW},
                {"wrapper.ntservice.password", PLANNED, YAJSW},
                {"wrapper.ntservice.interactive", IGNORED, YAJSW},
                {"wrapper.ntservice.failure_actions.reset", UNSUPPORTED, YAJSW},
                {"wrapper.ntservice.additional.1", PLANNED, YAJSW},
                // Out of scope
                {"wrapper.daemon.dir", UNSUPPORTED, YAJSW},
                {"wrapper.daemon.pid.dir", UNSUPPORTED, YAJSW},
                {"wrapper.daemon.run_level_dir", UNSUPPORTED, YAJSW},
                {"wrapper.daemon.update_rc", UNSUPPORTED, YAJSW},
                {"wrapper.tray", UNSUPPORTED, YAJSW},
                {"wrapper.tray.port", UNSUPPORTED, YAJSW},
                {"wrapper.tray.icon", UNSUPPORTED, YAJSW},
                {"wrapper.jmx", UNSUPPORTED, YAJSW},
                {"wrapper.jmx.rmi.port", UNSUPPORTED, YAJSW},
                {"wrapper.jmx.rmi.user", UNSUPPORTED, YAJSW},
                {"wrapper.jmx.rmi.password", UNSUPPORTED, YAJSW},
                // nyajsw extensions
                {"wrapper.readiness.type", PLANNED, NYAJSW},
                {"wrapper.readiness.interval", PLANNED, NYAJSW},
                {"wrapper.readiness.tcp.host", PLANNED, NYAJSW},
                {"wrapper.readiness.tcp.port", PLANNED, NYAJSW},
                {"wrapper.readiness.http.url", PLANNED, NYAJSW},
                {"wrapper.readiness.http.status", PLANNED, NYAJSW},
                {"wrapper.readiness.http.timeout", PLANNED, NYAJSW},
                {"wrapper.readiness.log.pattern", PLANNED, NYAJSW},
                {"wrapper.readiness.log.file", PLANNED, NYAJSW},
                {"wrapper.readiness.file.path", PLANNED, NYAJSW},
                {"wrapper.readiness.command", PLANNED, NYAJSW},
                {"wrapper.readiness.command.timeout", PLANNED, NYAJSW},
                {"wrapper.readiness.jmx.url", UNSUPPORTED, NYAJSW},
                {"wrapper.readiness.jmx.attribute", UNSUPPORTED, NYAJSW},
                {"wrapper.readiness.jmx.value", UNSUPPORTED, NYAJSW},
                {"wrapper.liveness.type", PLANNED, NYAJSW},
                {"wrapper.liveness.interval", PLANNED, NYAJSW},
                {"wrapper.liveness.failures", PLANNED, NYAJSW},
                {"wrapper.liveness.action", PLANNED, NYAJSW},
                {"wrapper.agent.enabled", PLANNED, NYAJSW},
                // Configuration file
                {"wrapper.conf.encoding", SUPPORTED, YAJSW},
        };
    }

    /** YAJSW keys declared after the check against the YAJSW reference (plan P1.10). */
    @DataProvider
    public Object[][] referenceKeys() {
        return new Object[][] {
                {"wrapper.config.script.decryptor", UNSUPPORTED_FATAL, YAJSW},
                {"wrapper.java.initialmemory.relative", UNSUPPORTED, YAJSW},
                {"wrapper.cleanup_tmp", UNSUPPORTED, YAJSW},
                {"wrapper.java.debug.port", UNSUPPORTED, YAJSW},
                {"wrapper.java.jmx", UNSUPPORTED, YAJSW},
                {"wrapper.registry.java_home", UNSUPPORTED, YAJSW},
                {"wrapper.app.pre.script", UNSUPPORTED, YAJSW},
                {"wrapper.app.pre_main.script", UNSUPPORTED, YAJSW},
                {"wrapper.codebase", UNSUPPORTED, YAJSW},
                {"wrapper.cache", UNSUPPORTED, YAJSW},
                {"wrapper.cache.local", UNSUPPORTED, YAJSW},
                {"wrapper.resource.1", UNSUPPORTED, YAJSW},
                {"wrapper.wrapperJar", IGNORED, YAJSW},
                {"wrapper.appJar", IGNORED, YAJSW},
                {"wrapper.startup.delay", PLANNED, YAJSW},
                {"wrapper.jvm_exit.timeout", PLANNED, YAJSW},
                {"wrapper.restart.reload_cache", UNSUPPORTED, YAJSW},
                {"wrapper.restart.delay.script", UNSUPPORTED, YAJSW},
                {"wrapper.restart.delay.script.args", UNSUPPORTED, YAJSW},
                {"wrapper.stopper", UNSUPPORTED, YAJSW},
                {"wrapper.lockfile", UNSUPPORTED, YAJSW},
                {"wrapper.exit_code.stop", UNSUPPORTED, YAJSW},
                {"wrapper.exit_code.kill", UNSUPPORTED, YAJSW},
                {"wrapper.exit_code.fatal", UNSUPPORTED, YAJSW},
                {"wrapper.script.1.ABORT", UNSUPPORTED, YAJSW},
                {"wrapper.script.ABORT.args", UNSUPPORTED, YAJSW},
                {"wrapper.script.reload", UNSUPPORTED, YAJSW},
                {"wrapper.script.encoding", UNSUPPORTED, YAJSW},
                {"wrapper.condition.script", UNSUPPORTED_FATAL, YAJSW},
                {"wrapper.condition.script.args", UNSUPPORTED, YAJSW},
                {"wrapper.condition.cycle", UNSUPPORTED, YAJSW},
                {"wrapper.timer.cron.RESTART", UNSUPPORTED, YAJSW},
                {"wrapper.timer.simple.RESTART.COUNT", UNSUPPORTED, YAJSW},
                {"wrapper.monitor.0.threshold", UNSUPPORTED, YAJSW},
                {"wrapper.filter.trigger-regex.1", UNSUPPORTED, YAJSW},
                {"wrapper.filter.script.1.timeout", UNSUPPORTED, YAJSW},
                {"wrapper.filter.script.1.maxConcInvoc", UNSUPPORTED, YAJSW},
                {"wrapper.filter.missing.trigger.1", UNSUPPORTED, YAJSW},
                {"wrapper.filter.missing.autostop.1", UNSUPPORTED, YAJSW},
                {"wrapper.filter.debug.default", UNSUPPORTED, YAJSW},
                {"wrapper.logfile.maxdays", PLANNED, YAJSW},
                {"wrapper.logfile.desc", UNSUPPORTED, YAJSW},
                {"wrapper.log.encoding", PLANNED, YAJSW},
                {"wrapper.console.minimized", UNSUPPORTED, YAJSW},
                {"wrapper.console.use_interpolated", UNSUPPORTED, YAJSW},
                {"wrapper.app.status.log.lines", UNSUPPORTED, YAJSW},
                {"wrapper.debug.level", PLANNED, YAJSW},
                {"wrapper.ntservice.java.command", PLANNED, YAJSW},
                {"wrapper.ntservice.java.customProcName", UNSUPPORTED, YAJSW},
                {"wrapper.ntservice.autoreport.startup", UNSUPPORTED, YAJSW},
                {"wrapper.ntservice.autoreport.waitready", UNSUPPORTED, YAJSW},
                {"wrapper.ntservice.reduce_signals", PLANNED, YAJSW},
                {"wrapper.ntservice.logon_active_session", UNSUPPORTED, YAJSW},
                {"wrapper.ntservice.desktop", UNSUPPORTED, YAJSW},
                {"wrapper.save_interpolated", IGNORED, YAJSW},
                {"wrapper.windows.cluster.groups", UNSUPPORTED_FATAL, YAJSW},
                {"wrapper.port", IGNORED, YAJSW},
                {"wrapper.port.min", IGNORED, YAJSW},
                {"wrapper.port.max", IGNORED, YAJSW},
                {"wrapper.daemon.template", UNSUPPORTED, YAJSW},
                {"wrapper.launchd.dir", UNSUPPORTED, YAJSW},
                {"wrapper.plist.template", UNSUPPORTED, YAJSW},
                {"wrapper.fork_hack", UNSUPPORTED, YAJSW},
                {"wrapper.posix_spawn", UNSUPPORTED, YAJSW},
                {"wrapper.posix_vfork", UNSUPPORTED, YAJSW},
                {"wrapper.tray.look_and_feel", UNSUPPORTED, YAJSW},
                {"wrapper.tray.text.dialog_stop", UNSUPPORTED, YAJSW},
        };
    }

    @Test(dataProvider = "referenceKeys")
    public void referenceKeyHasItsStatusAndOrigin(String key, KeyStatus status, KeyOrigin origin) {
        seededKeyHasItsStatusAndOrigin(key, status, origin);
    }

    @Test
    public void keysNotInTheYajswReferenceAreUnknown() {
        // wrapper.java.debug is not a YAJSW key (only wrapper.java.debug.port is), and the nyajsw extension
        // wrapper.ntservice.java.additional.<n> gave way to YAJSW's wrapper.ntservice.additional.<n>.
        assertEquals(REGISTRY.lookup("wrapper.java.debug"), Optional.empty());
        assertEquals(REGISTRY.lookup("wrapper.ntservice.java.additional.1"), Optional.empty());
    }

    @Test
    public void filterActionsHaveTheYajswValues() {
        assertEquals(ConfigKeys.FILTER_ACTION.values(), List.of("RESTART", "SHUTDOWN", "NONE"));
    }

    @Test
    public void rollModesHaveTheYajswValues() {
        assertEquals(ConfigKeys.LOGFILE_ROLLMODE.values(), List.of("JVM", "WRAPPER", "DATE"));
    }

    @Test(dataProvider = "seededKeys")
    public void seededKeyHasItsStatusAndOrigin(String key, KeyStatus status, KeyOrigin origin) {
        Optional<KeySpec> spec = REGISTRY.lookup(key);

        assertTrue(spec.isPresent(), "not declared: " + key);
        assertEquals(spec.get().status(), status, key);
        assertEquals(spec.get().origin(), origin, key);
    }

    @Test
    public void unknownAndMisspeltKeysAreNotFound() {
        assertEquals(REGISTRY.lookup("wrapper.java.commandx"), Optional.empty());
        assertEquals(REGISTRY.lookup("wrapper.java.additionalx.1"), Optional.empty());
        assertEquals(REGISTRY.lookup("Wrapper.java.command"), Optional.empty());
        assertEquals(REGISTRY.lookup("JAVA_HOME"), Optional.empty());
        // Misspelt exit-code keys are unknown, so they are warned about instead of misread.
        assertEquals(REGISTRY.lookup("wrapper.on_exit.defualt"), Optional.empty());
        assertEquals(REGISTRY.lookup("wrapper.on_exit.Default"), Optional.empty());
    }

    @Test
    public void indexedAndDynamicFamiliesResolveToTheirDeclaration() {
        assertEquals(REGISTRY.lookup("wrapper.java.additional.25").orElseThrow(), ConfigKeys.JAVA_ADDITIONAL);
        assertEquals(REGISTRY.lookup("wrapper.java.additional.jvm-opts").orElseThrow(), ConfigKeys.JAVA_ADDITIONAL);
        assertEquals(REGISTRY.lookup("wrapper.java.classpath.lib").orElseThrow(), ConfigKeys.JAVA_CLASSPATH);
        // Tanuki keys under an index family keep their own declaration instead of becoming members.
        assertEquals(REGISTRY.lookup("wrapper.java.additional.1.stripquotes").orElseThrow(),
                ConfigKeys.JAVA_ADDITIONAL_STRIPQUOTES);
        assertEquals(REGISTRY.lookup("wrapper.app.parameter.1.stripquotes").orElseThrow(),
                ConfigKeys.APP_PARAMETER_STRIPQUOTES);
        assertEquals(REGISTRY.lookup("wrapper.java.library.path.append_system_path").orElseThrow(),
                ConfigKeys.JAVA_LIBRARY_PATH_APPEND_SYSTEM_PATH);
        assertEquals(REGISTRY.lookup("wrapper.java.additional.auto_bits").orElseThrow(),
                ConfigKeys.JAVA_ADDITIONAL_AUTO_BITS);
        assertEquals(REGISTRY.lookup("wrapper.java.additional.auto_bits.windows-x86-64").orElseThrow(),
                ConfigKeys.JAVA_ADDITIONAL_AUTO_BITS_PLATFORM);
        assertEquals(REGISTRY.lookup("wrapper.java.classpath.use_environment").orElseThrow(),
                ConfigKeys.JAVA_CLASSPATH_USE_ENVIRONMENT);
        assertEquals(REGISTRY.lookup("wrapper.java.classpath.missing_log_level").orElseThrow(),
                ConfigKeys.JAVA_CLASSPATH_MISSING_LOG_LEVEL);
        // A dotted index before a suffix: the suffix family wins and the whole index is captured.
        assertEquals(REGISTRY.lookup("wrapper.filter.script.2.1.args").orElseThrow(), ConfigKeys.FILTER_SCRIPT_ARGS);
        assertEquals(ConfigKeys.FILTER_SCRIPT_ARGS.pattern().captures("wrapper.filter.script.2.1.args"),
                Optional.of(List.of("2.1")));
        assertEquals(REGISTRY.lookup("wrapper.ntservice.additional.2").orElseThrow(), ConfigKeys.NTSERVICE_ADDITIONAL);
        assertEquals(REGISTRY.lookup("wrapper.app.env.JAVA_OPTS").orElseThrow(), ConfigKeys.APP_ENV);
        assertEquals(REGISTRY.lookup("wrapper.on_exit.default").orElseThrow(), ConfigKeys.ON_EXIT_DEFAULT);
        assertEquals(REGISTRY.lookup("wrapper.on_exit.0").orElseThrow(), ConfigKeys.ON_EXIT_0);
        assertEquals(REGISTRY.lookup("wrapper.on_exit.1").orElseThrow(), ConfigKeys.ON_EXIT);
        assertEquals(REGISTRY.lookup("wrapper.on_exit.-1").orElseThrow(), ConfigKeys.ON_EXIT);
        assertEquals(REGISTRY.lookup("wrapper.filter.script.4.args").orElseThrow(), ConfigKeys.FILTER_SCRIPT_ARGS);
    }

    @Test
    public void passwordsAreSecret() {
        assertTrue(ConfigKeys.NTSERVICE_PASSWORD.secret());
        assertTrue(ConfigKeys.APP_PASSWORD.secret());
        assertTrue(ConfigKeys.JMX_RMI_PASSWORD.secret());
        assertTrue(REGISTRY.isSecret("wrapper.ntservice.password"));
        assertTrue(REGISTRY.isSecret("wrapper.app.password"));
        assertTrue(REGISTRY.isSecret("wrapper.jmx.rmi.password"));
        assertTrue(REGISTRY.isSecret("wrapper.unknown.password"));
        assertFalse(REGISTRY.isSecret("wrapper.ntservice.account"));
        for (KeySpec spec : REGISTRY.specs()) {
            if (spec.secret()) {
                assertEquals(spec.defaultValue(), KeyDefault.NONE, spec.name());
            }
        }
    }

    @Test
    public void documentedDefaults() {
        assertEquals(ConfigKeys.MAX_FAILED_INVOCATIONS.defaultValue(), KeyDefault.of("5"));
        assertEquals(ConfigKeys.SUCCESSFUL_INVOCATION_TIME.defaultValue(), KeyDefault.of("60"));
        assertEquals(ConfigKeys.STARTUP_TIMEOUT.defaultValue(), KeyDefault.of("30"));
        assertEquals(ConfigKeys.SHUTDOWN_TIMEOUT.defaultValue(), KeyDefault.of("30"));
        assertEquals(ConfigKeys.NTSERVICE_STARTTYPE.defaultValue(), KeyDefault.of("AUTO_START"));
        assertEquals(ConfigKeys.READINESS_TYPE.defaultValue(), KeyDefault.of("process"));
        assertEquals(ConfigKeys.READINESS_INTERVAL.defaultValue(), KeyDefault.of("1"));
        assertEquals(ConfigKeys.LIVENESS_INTERVAL.defaultValue(), KeyDefault.of("10"));
        assertEquals(ConfigKeys.LIVENESS_FAILURES.defaultValue(), KeyDefault.of("3"));
        assertEquals(ConfigKeys.AGENT_ENABLED.defaultValue(), KeyDefault.of("true"));
        assertEquals(ConfigKeys.CONTROL.defaultValue(), KeyDefault.of("TIGHT"));
        assertEquals(ConfigKeys.ON_EXIT_DEFAULT.defaultValue(), KeyDefault.of("SHUTDOWN"));
        // Without wrapper.on_exit.0, code 0 takes wrapper.on_exit.default like any other code.
        assertEquals(ConfigKeys.ON_EXIT_0.defaultValue(), KeyDefault.NONE);
        assertEquals(ConfigKeys.ON_EXIT.defaultValue(), KeyDefault.NONE);
        assertEquals(ConfigKeys.READINESS_TCP_HOST.defaultValue(), KeyDefault.of("127.0.0.1"));
        assertEquals(ConfigKeys.READINESS_HTTP_STATUS.defaultValue(), KeyDefault.of("200-399"));
        assertEquals(ConfigKeys.LIVENESS_ACTION.defaultValue(), KeyDefault.of("RESTART"));
        assertEquals(ConfigKeys.DEBUG.defaultValue(), KeyDefault.of("false"));
        assertEquals(ConfigKeys.JAVA_COMMAND_JAVAW.defaultValue(), KeyDefault.of("false"));
        assertEquals(ConfigKeys.RESTART_DELAY.defaultValue(), KeyDefault.of("5"));
        assertEquals(ConfigKeys.STARTUP_DELAY.defaultValue(), KeyDefault.of("0"));
        assertEquals(ConfigKeys.PING_INTERVAL.defaultValue(), KeyDefault.of("5"));
        assertEquals(ConfigKeys.PING_TIMEOUT.defaultValue(), KeyDefault.of("30"));
        assertEquals(ConfigKeys.LOGFILE_LOGLEVEL.defaultValue(), KeyDefault.of("INFO"));
        assertEquals(ConfigKeys.CONSOLE_LOGLEVEL.defaultValue(), KeyDefault.of("INFO"));
        assertEquals(ConfigKeys.LOGFILE_FORMAT.defaultValue(), KeyDefault.of("LPTM"));
        assertEquals(ConfigKeys.CONSOLE_FORMAT.defaultValue(), KeyDefault.of("PM"));
        assertEquals(ConfigKeys.LOGFILE_MAXSIZE.defaultValue(), KeyDefault.of("0"));
        assertEquals(ConfigKeys.LOGFILE_MAXDAYS.defaultValue(), KeyDefault.of("-1"));
        assertEquals(ConfigKeys.DEBUG_LEVEL.defaultValue(), KeyDefault.of("3"));
        assertEquals(ConfigKeys.FILTER_ACTION.defaultValue(), KeyDefault.of("RESTART"));
        assertEquals(ConfigKeys.NTSERVICE_REDUCE_SIGNALS.defaultValue(), KeyDefault.of("true"));
        assertTrue(ConfigKeys.WORKING_DIR.defaultValue() instanceof KeyDefault.Derived);
        assertTrue(ConfigKeys.STOP_STRATEGY.defaultValue() instanceof KeyDefault.Derived);
        assertTrue(ConfigKeys.LIVENESS_TYPE.defaultValue() instanceof KeyDefault.Derived);
    }

    @Test
    public void defaultsThePlanDoesNotConfirmAreMarkedForVerification() {
        for (KeySpec spec : List.of(ConfigKeys.SHUTDOWN_TIMEOUT, ConfigKeys.WORKING_DIR, ConfigKeys.NTSERVICE_NAME,
                ConfigKeys.JAVA_COMMAND_JAVAW, ConfigKeys.TMP_PATH)) {
            assertTrue(spec.unverified().contains(Unverified.DEFAULT), spec.name());
        }
        assertTrue(ConfigKeys.TRAY_PORT.unverified().contains(Unverified.NAME));
        assertTrue(ConfigKeys.LOGFILE_ROLLMODE.unverified().contains(Unverified.VALUES));
    }

    /** Parts of declarations the YAJSW reference confirmed (plan P1.10), so no longer marked. */
    @Test
    public void defaultsAndNamesTheReferenceConfirmsAreNotMarked() {
        for (KeySpec spec : List.of(ConfigKeys.CONTROL, ConfigKeys.ON_EXIT_DEFAULT, ConfigKeys.RESTART_DELAY,
                ConfigKeys.PING_INTERVAL, ConfigKeys.RESTART_RELOAD_CONFIGURATION, ConfigKeys.EXIT_ON_MAIN_TERMINATE,
                ConfigKeys.EXIT_ON_MAIN_EXCEPTION, ConfigKeys.CONSOLE_PIPESTREAMS, ConfigKeys.JAVA_MONITOR,
                ConfigKeys.SYSLOG, ConfigKeys.NTSERVICE_FAILURE_ACTIONS, ConfigKeys.LOGFILE_LOGLEVEL,
                ConfigKeys.CONSOLE_LOGLEVEL)) {
            assertTrue(spec.unverified().isEmpty(), spec.name() + " " + spec.unverified());
        }
    }

    @Test
    public void everyConstantDefaultParsesLikeAConfiguredValue() {
        for (KeySpec spec : REGISTRY.specs()) {
            if (spec.defaultValue() instanceof KeyDefault.Constant constant) {
                ValueParser.parse(spec, constant.value());
                assertFalse(ValueParser.isUnset(spec, constant.value()), spec.name());
            }
        }
    }

    @Test
    public void startTypeAcceptsTheDelayStartAlias() {
        assertEquals(ConfigKeys.NTSERVICE_STARTTYPE.canonicalValue("DELAY_START"), Optional.of("DELAYED_AUTO_START"));
        assertEquals(ConfigKeys.NTSERVICE_STARTTYPE.canonicalValue("DELAYED_AUTO_START"),
                Optional.of("DELAYED_AUTO_START"));
    }

    @Test
    public void logLevelsUseTheYajswNames() {
        List<String> levels = List.of("NONE", "FATAL", "ERROR", "STATUS", "INFO", "DEBUG");

        assertEquals(ConfigKeys.LOGFILE_LOGLEVEL.values(), levels);
        assertEquals(ConfigKeys.CONSOLE_LOGLEVEL.values(), levels);
    }
}
