package com.zachery.cms.common.exception;

import com.zachery.cms.common.api.FieldViolation;
import com.zachery.cms.common.api.ValidationErrorData;
import java.util.List;

public class RequestValidationException extends BusinessException {
    private final ValidationErrorData data;

    public RequestValidationException(List<FieldViolation> errors) {
        super(ErrorCode.COMMON_VALIDATION_ERROR);
        this.data = new ValidationErrorData(errors);
    }

    public RequestValidationException(String field, String message) {
        this(List.of(new FieldViolation(field, message)));
    }

    public ValidationErrorData getData() { return data; }
}
