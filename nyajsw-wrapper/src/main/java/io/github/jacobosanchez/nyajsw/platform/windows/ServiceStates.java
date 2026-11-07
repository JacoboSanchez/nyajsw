package io.github.jacobosanchez.nyajsw.platform.windows;

import io.github.jacobosanchez.nyajsw.platform.ServiceException;
import io.github.jacobosanchez.nyajsw.platform.ServiceState;

import java.util.Locale;
import java.util.Map;

/** The Service Control Manager's state codes as {@link ServiceState}s, and for messages. */
final class ServiceStates {

    private static final Map<Integer, ServiceState> BY_CODE = Map.of(
            ScmApi.SERVICE_STOPPED, ServiceState.STOPPED,
            ScmApi.SERVICE_START_PENDING, ServiceState.START_PENDING,
            ScmApi.SERVICE_STOP_PENDING, ServiceState.STOP_PENDING,
            ScmApi.SERVICE_RUNNING, ServiceState.RUNNING,
            ScmApi.SERVICE_CONTINUE_PENDING, ServiceState.CONTINUE_PENDING,
            ScmApi.SERVICE_PAUSE_PENDING, ServiceState.PAUSE_PENDING,
            ScmApi.SERVICE_PAUSED, ServiceState.PAUSED);

    private ServiceStates() {
    }

    /**
     * The state of {@code code}.
     *
     * @throws ServiceException for a code the Service Control Manager does not document
     */
    static ServiceState of(int code) {
        ServiceState state = BY_CODE.get(code);
        if (state == null) {
            throw new ServiceException("the Service Control Manager reports an unknown service state " + code, 0);
        }
        return state;
    }

    /** {@code code} for messages, such as {@code "start pending"}. */
    static String name(int code) {
        ServiceState state = BY_CODE.get(code);
        return state == null ? "in state " + code : state.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
