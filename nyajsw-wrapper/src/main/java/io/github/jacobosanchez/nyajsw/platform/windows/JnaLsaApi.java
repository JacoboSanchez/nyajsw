package io.github.jacobosanchez.nyajsw.platform.windows;

import io.github.jacobosanchez.nyajsw.platform.ServiceException;
import io.github.jacobosanchez.nyajsw.platform.windows.jna.Advapi32Lsa;
import io.github.jacobosanchez.nyajsw.platform.windows.jna.LsaObjectAttributes;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.platform.win32.NTSecApi;
import com.sun.jna.platform.win32.Win32Exception;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.PointerByReference;

import java.util.function.Supplier;

/**
 * {@link LsaApi} through {@code jna-platform}'s {@code Advapi32Util} (account lookup) and the
 * hand-written LSA binding {@link Advapi32Lsa}. The native library is loaded by the first call.
 */
final class JnaLsaApi implements LsaApi {

    @Override
    public byte[] userSid(String account) {
        Advapi32Util.Account found;
        try {
            found = Advapi32Util.getAccountByName(lookupName(account, Kernel32Util::getComputerName));
        } catch (Win32Exception e) {
            throw failure("LookupAccountName", e.getErrorCode());
        }
        if (found.accountType != WinNT.SID_NAME_USE.SidTypeUser) {
            throw new ServiceException("'" + account + "' is a group or another kind of account, not a user (SID"
                    + " type " + found.accountType + "); a service runs as one user account", 0);
        }
        return found.sid;
    }

    /**
     * {@code account} as {@code LookupAccountName} takes it: {@code .\name}, how a local account is
     * given to the Service Control Manager, becomes {@code <computer>\name}, so that a domain account
     * of the same name is never found instead.
     */
    static String lookupName(String account, Supplier<String> computerName) {
        return account.startsWith(".\\") ? computerName.get() + account.substring(1) : account;
    }

    @Override
    public void grantServiceLogon(byte[] sid) {
        PointerByReference policy = new PointerByReference();
        check(Advapi32Lsa.INSTANCE.LsaOpenPolicy(null, new LsaObjectAttributes(),
                Advapi32Lsa.POLICY_CREATE_ACCOUNT | Advapi32Lsa.POLICY_LOOKUP_NAMES, policy), "LsaOpenPolicy");
        try {
            Memory nativeSid = new Memory(sid.length);
            nativeSid.write(0, sid, 0, sid.length);
            check(Advapi32Lsa.INSTANCE.LsaAddAccountRights(policy.getValue(), nativeSid,
                    unicode(Advapi32Lsa.SE_SERVICE_LOGON_NAME), 1), "LsaAddAccountRights");
        } finally {
            Advapi32Lsa.INSTANCE.LsaClose(policy.getValue());
        }
    }

    /** {@code text} as an {@code LSA_UNICODE_STRING}: lengths in bytes, no terminating NUL counted. */
    private static NTSecApi.LSA_UNICODE_STRING unicode(String text) {
        Memory buffer = new Memory((text.length() + 1L) * Native.WCHAR_SIZE);
        buffer.setWideString(0, text);
        NTSecApi.LSA_UNICODE_STRING string = new NTSecApi.LSA_UNICODE_STRING();
        string.Length = (short) (text.length() * Native.WCHAR_SIZE);
        string.MaximumLength = (short) ((text.length() + 1) * Native.WCHAR_SIZE);
        string.Buffer = buffer;
        return string;
    }

    /** An {@code NTSTATUS} below 0 is an error ({@code NT_SUCCESS} is {@code status >= 0}). */
    private static void check(int status, String call) {
        if (status < 0) {
            throw failure(call, Advapi32Lsa.INSTANCE.LsaNtStatusToWinError(status));
        }
    }

    private static ServiceException failure(String call, int code) {
        return JnaScmApi.failure(call, code);
    }
}
