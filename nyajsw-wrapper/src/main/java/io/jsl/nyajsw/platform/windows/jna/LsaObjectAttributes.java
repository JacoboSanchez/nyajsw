package io.jsl.nyajsw.platform.windows.jna;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

/**
 * {@code LSA_OBJECT_ATTRIBUTES} (ntsecapi.h), for {@code LsaOpenPolicy}, which ignores its contents
 * but wants it zeroed with its {@code Length} set; {@code jna-platform} does not declare it (plan
 * §4.4).
 */
@Structure.FieldOrder({"Length", "RootDirectory", "ObjectName", "Attributes", "SecurityDescriptor",
        "SecurityQualityOfService"})
public class LsaObjectAttributes extends Structure {

    public int Length;
    public Pointer RootDirectory;
    public Pointer ObjectName;
    public int Attributes;
    public Pointer SecurityDescriptor;
    public Pointer SecurityQualityOfService;

    public LsaObjectAttributes() {
        Length = size();
    }
}
