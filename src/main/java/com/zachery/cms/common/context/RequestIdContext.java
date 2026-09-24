package com.zachery.cms.common.context;

import org.slf4j.MDC;

/** Holds the request identifier for the lifetime of one servlet request. */
public final class RequestIdContext {
    public static final String HEADER_NAME = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();

    private RequestIdContext() {}

    public static String getRequestId() {
        return REQUEST_ID.get();
    }

    public static void setRequestId(String requestId) {
        if (requestId == null) {
            clear();
            return;
        }
        REQUEST_ID.set(requestId);
        MDC.put(MDC_KEY, requestId);
    }

    public static void clear() {
        REQUEST_ID.remove();
        MDC.remove(MDC_KEY);
    }
}
