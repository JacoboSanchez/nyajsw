package io.jsl.nyajsw.platform.windows;

/**
 * What {@code QueryServiceStatusEx} reports about a service, as plain values ({@code
 * SERVICE_STATUS_PROCESS}).
 *
 * @param currentState            {@code dwCurrentState}, one of the {@code SERVICE_*} state codes
 * @param win32ExitCode           {@code dwWin32ExitCode}: why a stopped service stopped, 0 when it
 *                                ended normally
 * @param serviceSpecificExitCode {@code dwServiceSpecificExitCode}, meaningful when the Win32 exit
 *                                code is {@code ERROR_SERVICE_SPECIFIC_ERROR}
 * @param waitHint                {@code dwWaitHint} in milliseconds, while a state change is pending
 * @param processId               {@code dwProcessId}, 0 while the service has no process
 */
record ScmStatus(int currentState, int win32ExitCode, int serviceSpecificExitCode, int waitHint, int processId) {
}
