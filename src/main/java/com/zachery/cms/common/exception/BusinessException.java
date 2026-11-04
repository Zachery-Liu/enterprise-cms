package com.zachery.cms.common.exception;

import org.springframework.http.HttpStatus;

import java.util.Objects;

/** Safe, client-facing business error with an explicit HTTP and business code. */
public class BusinessException extends RuntimeException {
    private final HttpStatus httpStatus;
    private final String code;

    public BusinessException(HttpStatus httpStatus, String code, String message) {
        super(message);
        this.httpStatus = Objects.requireNonNull(httpStatus, "httpStatus");
        this.code = requireText(code, "code");
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getCode() {
        return code;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
