package com.aiconnecting.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Request-scoped failure metadata shared by controller, relay layer and the unified error outlet. */
public final class FailureLogContext {
    public static final String MODEL_NAME = FailureLogContext.class.getName() + ".modelName";
    public static final String CHANNEL_MODEL_NAME = FailureLogContext.class.getName() + ".channelModelName";
    public static final String PROTOCOL = FailureLogContext.class.getName() + ".protocol";
    public static final String CHANNEL_ERROR = FailureLogContext.class.getName() + ".channelError";
    public static final String TRACE_ID = FailureLogContext.class.getName() + ".traceId";
    public static final String RECORDED = FailureLogContext.class.getName() + ".recorded";
    public static final String RECORDED_CHANNEL_FAILURES = FailureLogContext.class.getName() + ".recordedChannelFailures";

    private FailureLogContext() {}

    public static void initialize(HttpServletRequest request, String modelName, RelayProtocol protocol) {
        if (request == null) return;
        if (modelName != null && !modelName.isBlank()) request.setAttribute(MODEL_NAME, modelName);
        if (protocol != null) request.setAttribute(PROTOCOL, protocol.name());
    }

    public static void setChannelModel(String channelModelName) {
        set(CHANNEL_MODEL_NAME, channelModelName);
    }

    public static void setChannelError(int status, String rawBody) {
        String body = rawBody == null ? "" : rawBody;
        set(CHANNEL_ERROR, "Upstream API error: " + status + " - " + body);
    }

    public static void setChannelError(String error) {
        set(CHANNEL_ERROR, error);
    }

    private static void set(String key, String value) {
        if (value == null || value.isBlank()) return;
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            servlet.getRequest().setAttribute(key, value);
        }
    }
}
