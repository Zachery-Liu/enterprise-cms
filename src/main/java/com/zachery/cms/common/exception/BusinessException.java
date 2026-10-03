package com.zachery.cms.common.exception;

import org.springframework.http.HttpStatus;
import java.util.Objects;

/** message is public API text: never pass a raw SQL or infrastructure exception message. */
public class BusinessException extends RuntimeException {
    private final HttpStatus httpStatus;
    private final String code;

    public BusinessException(HttpStatus httpStatus, String code, String message) {
        super(message);
        this.httpStatus = Objects.requireNonNull(httpStatus);
        if (!httpStatus.isError()) throw new IllegalArgumentException("An error status is required");
        this.code = Objects.requireNonNull(code);
    }

    public BusinessException(ErrorCode errorCode) {
        this(errorCode.getHttpStatus(), errorCode.name(), errorCode.getMessage());
    }

    public HttpStatus getHttpStatus() { return httpStatus; }
    public String getCode() { return code; }
}
