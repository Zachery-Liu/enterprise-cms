package com.zachery.cms.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    COMMON_VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "请求参数不合法"),
    AUTH_UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "请先登录"),
    AUTH_FORBIDDEN(HttpStatus.FORBIDDEN, "没有操作权限"),
    COMMON_NOT_FOUND(HttpStatus.NOT_FOUND, "记录不存在"),
    COMMON_CONFLICT(HttpStatus.CONFLICT, "数据冲突，请刷新后重试"),
    COMMON_INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务暂时无法处理请求，请凭请求标识联系管理员");

    private final HttpStatus httpStatus;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public HttpStatus getHttpStatus() { return httpStatus; }
    public String getMessage() { return message; }
}
