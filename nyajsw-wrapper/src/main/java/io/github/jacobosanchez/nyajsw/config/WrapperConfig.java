package io.github.jacobosanchez.nyajsw.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The validated, typed configuration (plan §4.5): everything outside the {@code config} package reads
 * configuration through these records, never raw strings. Build one with {@link #from(LoadedConfig)},
 * typically on the result of {@link ConfigLoader#load(Path)}.
 *
 * <p>Secrets are {@link Secret}s, so the generated {@code toString()} of the records never shows them.
 *
 * @param file      the main configuration file
 * @param java      the application process
 * @param service   the Windows service
 * @param log       wrapper logging
 * @param lifecycle supervision
 * @param probes    readiness, liveness and the agent
 * @param warnings  everything worth telling the operator, from loading, validation and typing, in
 *                  that order
 * @param secrets   hides the secrets of this configuration in text built from its values, such as
 *                  the logged command line ({@code launch.JavaCommand})
 */
public record WrapperConfig(Path file, JavaConfig java, ServiceConfig service, LogConfig log,
        LifecycleConfig lifecycle, ProbeConfig probes, List<ConfigWarning> warnings, SecretMasker secrets) {

    public WrapperConfig {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(java, "java");
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(log, "log");
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(probes, "probes");
        warnings = List.copyOf(warnings);
        Objects.requireNonNull(secrets, "secrets");
    }

    /**
     * Hides values: free text such as parameters, commands or URLs can contain a secret copied in
     * with <code>${...}</code>. {@link ConfigDump} is the sanctioned way to show values.
     */
    @Override
    public String toString() {
        return "WrapperConfig[values hidden; see ConfigDump]";
    }

    /**
     * Validates {@code config} against the nyajsw key registry and builds the typed views.
     *
     * @throws ConfigException listing every error found, from validation and typing together
     */
    public static WrapperConfig from(LoadedConfig config) {
        KeyRegistry registry = ConfigKeys.registry();
        ConfigValidator.Result validation = new ConfigValidator(registry).validate(config);
        ConfigValues values = new ConfigValues(config, registry);
        JavaConfig java = JavaConfig.from(values);
        ProbeConfig probes = ProbeConfig.from(values);
        ServiceConfig service = ServiceConfig.from(values, java);
        LogConfig log = LogConfig.from(values);
        LifecycleConfig lifecycle = LifecycleConfig.from(values, ProbeConfig.agentInEffect(probes.agentEnabled()));
        List<ConfigException> errors = new ArrayList<>(validation.errors());
        errors.addAll(values.errors());
        if (!errors.isEmpty()) {
            throw ConfigException.aggregate(errors);
        }
        List<ConfigWarning> warnings = new ArrayList<>(config.warnings());
        warnings.addAll(validation.warnings());
        warnings.addAll(values.warnings());
        return new WrapperConfig(config.file(), java, service, log, lifecycle, probes, warnings, values.masker());
    }
}
