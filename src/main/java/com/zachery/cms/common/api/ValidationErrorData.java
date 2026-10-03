package com.zachery.cms.common.api;

import java.util.List;

public record ValidationErrorData(List<FieldViolation> errors) {
    public ValidationErrorData {
        errors = List.copyOf(errors);
    }
}
