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
                {"wrapper.console.pipestreams", UNSUPPORTED, YAJSW},
                {"wrapper.debug", PLANNED, YAJSW},
                {"wrapper.java.debug", PLANNED, YAJSW},
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
                {"wrapper.ntservice.java.additional.1", PLANNED, NYAJSW},
                // Out of scope
                {"wrapper.daemon.dir", UNSUPPORTED, YAJSW},
                {"wrapper.daemon.pid.dir", UNSUPPORTED, YAJSW},
                {"wrapper.daemon.run_level_dir", UNSUPPORTED, YAJSW},
                {"wrapper.update_rc", UNSUPPORTED, YAJSW},
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
                {"wrapper.filter.action.1", PLANNED, NYAJSW},
                // Configuration file
                {"wrapper.conf.encoding", SUPPORTED, YAJSW},
        };
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
        assertEquals(REGISTRY.lookup("wrapper.java.additional.x"), Optional.empty());
        assertEquals(REGISTRY.lookup("Wrapper.java.command"), Optional.empty());
        assertEquals(REGISTRY.lookup("JAVA_HOME"), Optional.empty());
        // Misspelt exit-code keys are unknown, so they are warned about instead of misread.
        assertEquals(REGISTRY.lookup("wrapper.on_exit.defualt"), Optional.empty());
        assertEquals(REGISTRY.lookup("wrapper.on_exit.Default"), Optional.empty());
    }

    @Test
    public void indexedAndDynamicFamiliesResolveToTheirDeclaration() {
        assertEquals(REGISTRY.lookup("wrapper.java.additional.25").orElseThrow(), ConfigKeys.JAVA_ADDITIONAL);
        assertEquals(REGISTRY.lookup("wrapper.ntservice.java.additional.2").orElseThrow(),
                ConfigKeys.NTSERVICE_JAVA_ADDITIONAL);
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
        assertEquals(ConfigKeys.SUCCESSFUL_INVOCATION_TIME.defaultValue(), KeyDefault.of("300"));
        assertEquals(ConfigKeys.STARTUP_TIMEOUT.defaultValue(), KeyDefault.of("30"));
        assertEquals(ConfigKeys.SHUTDOWN_TIMEOUT.defaultValue(), KeyDefault.of("30"));
        assertEquals(ConfigKeys.NTSERVICE_STARTTYPE.defaultValue(), KeyDefault.of("AUTO_START"));
        assertEquals(ConfigKeys.READINESS_TYPE.defaultValue(), KeyDefault.of("process"));
        assertEquals(ConfigKeys.READINESS_INTERVAL.defaultValue(), KeyDefault.of("1"));
        assertEquals(ConfigKeys.LIVENESS_INTERVAL.defaultValue(), KeyDefault.of("10"));
        assertEquals(ConfigKeys.LIVENESS_FAILURES.defaultValue(), KeyDefault.of("3"));
        assertEquals(ConfigKeys.AGENT_ENABLED.defaultValue(), KeyDefault.of("true"));
        assertEquals(ConfigKeys.CONTROL.defaultValue(), KeyDefault.of("TIGHT"));
        assertEquals(ConfigKeys.ON_EXIT_DEFAULT.defaultValue(), KeyDefault.of("RESTART"));
        assertEquals(ConfigKeys.ON_EXIT_0.defaultValue(), KeyDefault.of("SHUTDOWN"));
        assertEquals(ConfigKeys.ON_EXIT.defaultValue(), KeyDefault.NONE);
        assertEquals(ConfigKeys.READINESS_TCP_HOST.defaultValue(), KeyDefault.of("127.0.0.1"));
        assertEquals(ConfigKeys.READINESS_HTTP_STATUS.defaultValue(), KeyDefault.of("200-399"));
        assertEquals(ConfigKeys.LIVENESS_ACTION.defaultValue(), KeyDefault.of("RESTART"));
        assertEquals(ConfigKeys.DEBUG.defaultValue(), KeyDefault.of("false"));
        assertEquals(ConfigKeys.JAVA_COMMAND_JAVAW.defaultValue(), KeyDefault.of("false"));
        assertTrue(ConfigKeys.WORKING_DIR.defaultValue() instanceof KeyDefault.Derived);
        assertTrue(ConfigKeys.STOP_STRATEGY.defaultValue() instanceof KeyDefault.Derived);
        assertTrue(ConfigKeys.LIVENESS_TYPE.defaultValue() instanceof KeyDefault.Derived);
    }

    @Test
    public void defaultsThePlanDoesNotConfirmAreMarkedForVerification() {
        for (KeySpec spec : List.of(ConfigKeys.CONTROL, ConfigKeys.ON_EXIT_DEFAULT, ConfigKeys.ON_EXIT_0,
                ConfigKeys.RESTART_DELAY, ConfigKeys.WORKING_DIR, ConfigKeys.NTSERVICE_NAME,
                ConfigKeys.JAVA_COMMAND_JAVAW)) {
            assertTrue(spec.unverified().contains(Unverified.DEFAULT), spec.name());
        }
        assertTrue(ConfigKeys.PING_INTERVAL.unverified().contains(Unverified.NAME));
        assertTrue(ConfigKeys.LOGFILE_ROLLMODE.unverified().contains(Unverified.VALUES));
        assertTrue(ConfigKeys.LOGFILE_LOGLEVEL.unverified().contains(Unverified.VALUES));
        assertTrue(ConfigKeys.JAVA_DEBUG.unverified().contains(Unverified.MEANING));
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
