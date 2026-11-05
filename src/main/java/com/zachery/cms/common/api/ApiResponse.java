package com.zachery.cms.common.api;

import com.zachery.cms.common.context.RequestContext;

public record ApiResponse<T>(String code, String message, T data, String requestId) {
    public static <T> ApiResponse<T> success(T data) { return success("操作成功", data); }
    public static <T> ApiResponse<T> success(String message, T data) { return new ApiResponse<>("OK", message, data, RequestContext.requestId()); }
    public static <T> ApiResponse<T> failure(String code, String message) { return failure(code, message, null); }
    public static <T> ApiResponse<T> failure(String code, String message, T data) { return error(code, message, data); }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>("OK", "成功", data, RequestContext.requestId());
    }

    public static ApiResponse<Void> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> error(String code, String message, T data) {
        return new ApiResponse<>(code, message, data, RequestContext.requestId());
    }
}
