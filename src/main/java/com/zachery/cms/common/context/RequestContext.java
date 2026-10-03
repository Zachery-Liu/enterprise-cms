package com.zachery.cms.common.context;

import java.util.UUID;

public final class RequestContext {
    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    private static final ThreadLocal<String> ID = new ThreadLocal<>();

    private RequestContext() {}

    public static String requestId() {
        String id = ID.get();
        // Calls outside HTTP still get an identifier without retaining thread state.
        return id == null ? UUID.randomUUID().toString() : id;
    }

    static void set(String id) { ID.set(id); }
    public static void clear() { ID.remove(); }
}
