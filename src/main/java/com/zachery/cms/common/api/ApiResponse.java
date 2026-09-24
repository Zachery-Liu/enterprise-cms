package com.zachery.cms.common.api;

import com.zachery.cms.common.context.RequestIdContext;

/** Standard response envelope shared by all CMS endpoints. */
public record ApiResponse<T>(String code, String message, T data, String requestId) {
    public static <T> ApiResponse<T> success(T data) {
        return success("操作成功", data);
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>("OK", message, data, RequestIdContext.getRequestId());
    }

    public static <T> ApiResponse<T> failure(String code, String message) {
        return failure(code, message, null);
    }

    public static <T> ApiResponse<T> failure(String code, String message, T data) {
        return new ApiResponse<>(code, message, data, RequestIdContext.getRequestId());
    }
}
