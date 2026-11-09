package io.jsl.nyajsw.platform.windows;

import io.jsl.nyajsw.config.Secret;
import io.jsl.nyajsw.config.StartType;

import com.sun.jna.platform.win32.WinNT;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What {@code CreateService} registers (plan §4.4): an own-process service that runs {@code
 * binaryPath}. The SCM's codes are {@code jna-platform}'s constants, inlined by the compiler.
 *
 * @param name         the service name
 * @param displayName  the name the Services console shows
 * @param binaryPath   the command line ({@link ServiceCommand#binaryPath})
 * @param startType    when Windows starts the service; {@code DELAYED_AUTO_START} registers as
 *                     automatic, made delayed by a second call
 * @param dependencies the services that must run first
 * @param account      the account the service runs as; empty for LocalSystem
 * @param password     the account's password, if it has one
 */
record ServiceRegistration(String name, String displayName, String binaryPath, StartType startType,
        List<String> dependencies, Optional<String> account, Optional<Secret> password) {

    ServiceRegistration {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(binaryPath, "binaryPath");
        Objects.requireNonNull(startType, "startType");
        dependencies = List.copyOf(dependencies);
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(password, "password");
    }

    /**
     * The start type as {@code CreateService} takes it: {@code DELAYED_AUTO_START} is automatic, made
     * delayed by {@code ChangeServiceConfig2} afterwards.
     */
    int scmStartType() {
        return switch (startType) {
            case AUTO_START, DELAYED_AUTO_START -> WinNT.SERVICE_AUTO_START;
            case DEMAND_START -> WinNT.SERVICE_DEMAND_START;
            case DISABLED -> WinNT.SERVICE_DISABLED;
        };
    }

    /**
     * The dependencies as {@code CreateService} takes them: each name ended by a NUL, the list
     * ended by one more, which the conversion to a native string adds; {@code null} for none.
     */
    String dependencyList() {
        if (dependencies.isEmpty()) {
            return null;
        }
        StringBuilder list = new StringBuilder();
        dependencies.forEach(dependency -> list.append(dependency).append('\0'));
        return list.toString();
    }

    /** Hides the binary path, which may hold a secret copied in with <code>${...}</code>, and the password. */
    @Override
    public String toString() {
        return "ServiceRegistration[" + name + ", values hidden]";
    }
}
