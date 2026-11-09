package io.jsl.nyajsw.platform.windows;

import io.jsl.nyajsw.platform.ServiceException;

/**
 * The account calls install makes besides the Service Control Manager's (plan §4.4, P5.5), in plain
 * types so that the controller can be tested with mocks. {@link JnaLsaApi} is the implementation;
 * each method throws a {@link ServiceException} carrying the Win32 error code and its text.
 */
interface LsaApi {

    /**
     * {@code LookupAccountName}: the security identifier of {@code account}, which must be a user
     * (a group such as {@code Users} would let all its members log on as a service); a local
     * account {@code .\name} is looked up on this computer only.
     */
    byte[] userSid(String account);

    /**
     * {@code LsaOpenPolicy}, {@code LsaAddAccountRights} with {@code SeServiceLogonRight} and {@code
     * LsaClose}: lets the account of {@code sid} log on as a service. An account that has the right
     * already keeps it; nothing changes.
     */
    void grantServiceLogon(byte[] sid);
}
