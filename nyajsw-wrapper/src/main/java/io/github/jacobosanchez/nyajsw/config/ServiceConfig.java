package io.github.jacobosanchez.nyajsw.config;

import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.NTSERVICE_ACCOUNT;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.NTSERVICE_ADDITIONAL;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.NTSERVICE_DEPENDENCY;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.NTSERVICE_DESCRIPTION;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.NTSERVICE_DISPLAYNAME;
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
 */
public record ServiceConfig(Optional<String> name, Optional<String> displayName, Optional<String> description,
        StartType startType, List<String> dependencies, Optional<String> account, Optional<Secret> password,
        List<String> wrapperJvmOptions) {

    public ServiceConfig {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(startType, "startType");
        dependencies = List.copyOf(dependencies);
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(password, "password");
        wrapperJvmOptions = List.copyOf(wrapperJvmOptions);
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
                values.familyValues(NTSERVICE_ADDITIONAL));
    }

    /** {@code com.example.Main} gives {@code Main}; a nested {@code Outer$Inner} gives {@code Inner}. */
    static String simpleName(String className) {
        String simple = className.substring(className.lastIndexOf('.') + 1);
        return simple.substring(simple.lastIndexOf('$') + 1);
    }
}
