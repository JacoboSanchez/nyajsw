package io.github.jacobosanchez.nyajsw.platform.windows;

import java.time.Duration;
import java.util.Objects;

/**
 * A status a service reports with {@code SetServiceStatus} ({@code SERVICE_STATUS} of an
 * own-process service), as plain values.
 *
 * @param currentState            {@code dwCurrentState}, one of the {@code SERVICE_*} state codes
 * @param controlsAccepted        {@code dwControlsAccepted}, the {@code SERVICE_ACCEPT_*} flags
 * @param win32ExitCode           {@code dwWin32ExitCode}
 * @param serviceSpecificExitCode {@code dwServiceSpecificExitCode}, read when the Win32 exit code is
 *                                {@code ERROR_SERVICE_SPECIFIC_ERROR}
 * @param checkPoint              {@code dwCheckPoint}, counting up while a state change is pending
 * @param waitHint                {@code dwWaitHint}: when the next report is due while pending
 */
record StatusReport(int currentState, int controlsAccepted, int win32ExitCode, int serviceSpecificExitCode,
        int checkPoint, Duration waitHint) {

    StatusReport {
        Objects.requireNonNull(waitHint, "waitHint");
    }
}
