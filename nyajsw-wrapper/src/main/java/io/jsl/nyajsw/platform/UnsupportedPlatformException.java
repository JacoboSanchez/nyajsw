package io.jsl.nyajsw.platform;

/**
 * What was asked needs a platform this host is not, such as the Windows service commands on Linux.
 * The command line ends with its own exit code for it ({@code cli.ExitCode#UNSUPPORTED_PLATFORM}),
 * apart from an operating system failure ({@link PlatformException}) on a platform that does offer
 * the operation.
 */
public class UnsupportedPlatformException extends PlatformException {

    private static final long serialVersionUID = 1L;

    public UnsupportedPlatformException(String message) {
        super(message);
    }
}
