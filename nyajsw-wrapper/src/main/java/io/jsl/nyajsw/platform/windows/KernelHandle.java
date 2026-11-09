package io.jsl.nyajsw.platform.windows;

/**
 * A kernel object handle (a job object, a process), opaque to everything but {@link JnaProcessApi}:
 * a plain value, so that {@link ProcessApi} can be mocked without native code. It is not an {@link
 * ScmHandle}: the two are closed by different calls ({@code CloseHandle}, {@code
 * CloseServiceHandle}), and the types keep them from being mixed up.
 *
 * @param value the handle's native value
 */
record KernelHandle(long value) {

    KernelHandle {
        if (value == 0) {
            throw new IllegalArgumentException("a null handle");
        }
    }
}
