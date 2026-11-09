package io.jsl.nyajsw.platform.windows;

/**
 * A handle the Service Control Manager gave out (to itself, to a service, or a service's status
 * handle), opaque to everything but the {@code Jna*} facades: a plain value, so that {@link ScmApi}
 * and {@link ServiceHostApi} can be mocked without native code.
 *
 * @param value the handle's native value
 */
record ScmHandle(long value) {

    ScmHandle {
        if (value == 0) {
            throw new IllegalArgumentException("a null handle");
        }
    }
}
