package io.jsl.nyajsw.platform.windows.jna;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.NTSecApi;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * The LSA policy functions of {@code advapi32} that grant an account the right to log on as a
 * service; {@code jna-platform} does not declare them (plan §4.4). They return an {@code
 * NTSTATUS}, 0 for success, which {@link #LsaNtStatusToWinError} turns into a Win32 error code. The
 * library is loaded when {@link #INSTANCE} is first read.
 */
public interface Advapi32Lsa extends StdCallLibrary {

    Advapi32Lsa INSTANCE = Native.load("advapi32", Advapi32Lsa.class, W32APIOptions.DEFAULT_OPTIONS);

    /** {@code POLICY_CREATE_ACCOUNT}: add rights to an account the policy does not list yet. */
    int POLICY_CREATE_ACCOUNT = 0x00000010;

    /** {@code POLICY_LOOKUP_NAMES}: what {@code LsaAddAccountRights} needs besides. */
    int POLICY_LOOKUP_NAMES = 0x00000800;

    /** The right to log on as a service, {@code SE_SERVICE_LOGON_NAME}. */
    String SE_SERVICE_LOGON_NAME = "SeServiceLogonRight";

    int LsaOpenPolicy(NTSecApi.LSA_UNICODE_STRING systemName, LsaObjectAttributes objectAttributes,
            int desiredAccess, PointerByReference policyHandle);

    int LsaAddAccountRights(Pointer policyHandle, Pointer accountSid, NTSecApi.LSA_UNICODE_STRING userRights,
            int countOfRights);

    int LsaClose(Pointer policyHandle);

    int LsaNtStatusToWinError(int status);
}
