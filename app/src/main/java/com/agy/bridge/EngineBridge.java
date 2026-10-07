package com.agy.bridge;

import com.agy.core.CoreRuntimeState;
import com.agy.runtime.RuntimeManager;
import com.agy.util.CoreUrl;

/** Read-only engine status exposed to the Web UI. */
public final class EngineBridge {

    private final RuntimeManager runtime;

    public EngineBridge(RuntimeManager runtime) {
        this.runtime = runtime;
    }

    public boolean isReady() {
        return CoreRuntimeState.serverUrl != null;
    }

    public int getPort() {
        return runtime.getHttpsPort();
    }

    public String getBaseUrl() {
        String url = CoreRuntimeState.serverUrl;
        if (url != null) {
            int q = url.indexOf('?');
            return q >= 0 ? url.substring(0, q) : url;
        }
        return CoreUrl.baseUrl(runtime.getHttpsPort());
    }

    public String getCsrfToken() {
        String token = CoreRuntimeState.csrfToken;
        return token != null ? token : runtime.getCsrfToken();
    }
}
