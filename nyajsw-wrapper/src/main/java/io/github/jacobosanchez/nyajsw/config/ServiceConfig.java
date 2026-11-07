package io.github.jacobosanchez.nyajsw.config;

import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.JAVA_APP_MAINCLASS;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.NTSERVICE_ACCOUNT;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.NTSERVICE_ADDITIONAL;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.NTSERVICE_DEPENDENCY;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.NTSERVICE_DESCRIPTION;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.NTSERVICE_DISPLAYNAME;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.NTSERVICE_JAVA_COMMAND;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.NTSERVICE_NAME;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.NTSERVICE_PASSWORD;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.NTSERVICE_STARTTYPE;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The Windows service as configured (plan §4.4).
 *
 * @param name            {@code wrapper.ntservice.name}, by default the simple name of the
 *                        application main class; empty when neither is set
 * @param displayName     {@code wrapper.ntservice.displayname}, if set
 * @param description     {@code wrapper.ntservice.description}, if set
 * @param startType       {@code wrapper.ntservice.starttype}
 * @param dependencies    {@code wrapper.ntservice.dependency.<n>} in index order
 * @param account         {@code wrapper.ntservice.account}, doubled backslashes undone as in paths
 *                        ({@code DOMAIN\\svc} gives {@code DOMAIN\svc}); empty means LocalSystem
 * @param password        {@code wrapper.ntservice.password}, if set
 * @param wrapperJvmOptions {@code wrapper.ntservice.additional.<n>} in index order
 * @param javaCommand     {@code wrapper.ntservice.java.command}, if set: like {@code
 *                        wrapper.java.command}, a bare name stays as it is and anything with a
 *                        directory is resolved, against the configuration file's directory, since the
 *                        service has no working directory of its own when it starts
 */
public record ServiceConfig(Optional<String> name, Optional<String> displayName, Optional<String> description,
        StartType startType, List<String> dependencies, Optional<String> account, Optional<Secret> password,
        List<String> wrapperJvmOptions, Optional<String> javaCommand) {

    public ServiceConfig {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(startType, "startType");
        dependencies = List.copyOf(dependencies);
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(password, "password");
        wrapperJvmOptions = List.copyOf(wrapperJvmOptions);
        Objects.requireNonNull(javaCommand, "javaCommand");
    }

    /**
     * Whether the service's account must be granted the right to log on as a service: not for no
     * account (LocalSystem), not for Windows' built-in service accounts ({@code LocalSystem}, {@code
     * SYSTEM}, {@code LocalService}, {@code NetworkService}, the last two also with a space, alone or
     * after {@code .\} or {@code NT AUTHORITY\}, in any case), and not for a virtual account
     * ({@code NT SERVICE\<name>}), which has the right through {@code NT SERVICE\ALL SERVICES} and
     * cannot even be looked up before its service exists.
     */
    public boolean needsServiceLogonRight() {
        if (account.isEmpty()) {
            return false;
        }
        String name = account.get().strip().toLowerCase(java.util.Locale.ROOT);
        if (name.startsWith("nt service\\")) {
            return false;
        }
        for (String prefix : List.of(".\\", "nt authority\\")) {
            if (name.startsWith(prefix)) {
                name = name.substring(prefix.length());
                break;
            }
        }
        return !List.of("localsystem", "system", "localservice", "local service", "networkservice",
                "network service").contains(name);
    }

    /**
     * The service's name, which every service command needs.
     *
     * @throws ConfigException when neither {@code wrapper.ntservice.name} nor a main class names it
     */
    public String requiredName() {
        return name.orElseThrow(() -> new ConfigException(NTSERVICE_NAME.name() + " is not set, and there is no "
                + JAVA_APP_MAINCLASS.name() + " to name the service after"));
    }

    /**
     * Hides values: free text such as parameters, commands or URLs can contain a secret copied in
     * with <code>${...}</code>. {@link ConfigDump} is the sanctioned way to show values.
     */
    @Override
    public String toString() {
        return "ServiceConfig[values hidden; see ConfigDump]";
    }

    static ServiceConfig from(ConfigValues values, JavaConfig java) {
        return new ServiceConfig(
                values.text(NTSERVICE_NAME).or(() -> java.mainClass().map(ServiceConfig::simpleName)),
                values.text(NTSERVICE_DISPLAYNAME),
                values.text(NTSERVICE_DESCRIPTION),
                values.required(values.enumeration(NTSERVICE_STARTTYPE, StartType.class), NTSERVICE_STARTTYPE),
                values.familyValues(NTSERVICE_DEPENDENCY),
                values.text(NTSERVICE_ACCOUNT).map(ValueParser::normalisePath),
                values.text(NTSERVICE_PASSWORD).map(Secret::new),
                values.familyValues(NTSERVICE_ADDITIONAL),
                JavaConfig.executable(values, NTSERVICE_JAVA_COMMAND, values.directory()));
    }

    /** {@code com.example.Main} gives {@code Main}; a nested {@code Outer$Inner} gives {@code Inner}. */
    static String simpleName(String className) {
        String simple = className.substring(className.lastIndexOf('.') + 1);
        return simple.substring(simple.lastIndexOf('$') + 1);
    }
}
