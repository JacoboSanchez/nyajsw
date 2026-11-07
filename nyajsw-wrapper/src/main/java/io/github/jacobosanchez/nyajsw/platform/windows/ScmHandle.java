package io.github.jacobosanchez.nyajsw.platform.windows;

/**
 * A handle the Service Control Manager gave out (to itself or to a service), opaque to everything
 * but {@link JnaScmApi}: a plain value, so that {@link ScmApi} can be mocked without native code.
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
