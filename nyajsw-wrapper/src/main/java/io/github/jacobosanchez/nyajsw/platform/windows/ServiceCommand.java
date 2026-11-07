package io.github.jacobosanchez.nyajsw.platform.windows;

import io.github.jacobosanchez.nyajsw.config.SecretMasker;

import java.util.List;
import java.util.Objects;

/**
 * The command line of a service, built by {@link ServiceCommandLine}.
 *
 * @param arguments the arguments, the executable first
 * @param warnings  what the operator should know about it, for the caller to report
 */
public record ServiceCommand(List<String> arguments, List<String> warnings) {

    public ServiceCommand {
        arguments = List.copyOf(arguments);
        warnings = List.copyOf(warnings);
    }

    /** The one string Windows stores as the service's binary path, quoted ({@link WindowsArguments}). */
    public String binaryPath() {
        return WindowsArguments.join(arguments);
    }

    /**
     * The binary path for the log: each argument masked before it is quoted, so that quoting cannot
     * hide a secret from the mask. Not a command to run.
     */
    public String loggable(SecretMasker secrets) {
        Objects.requireNonNull(secrets, "secrets");
        return WindowsArguments.join(arguments.stream().map(secrets::maskText).toList());
    }

    /** Hides the arguments, which may hold a secret copied in with <code>${...}</code>. */
    @Override
    public String toString() {
        return "ServiceCommand[values hidden; see loggable]";
    }
}
