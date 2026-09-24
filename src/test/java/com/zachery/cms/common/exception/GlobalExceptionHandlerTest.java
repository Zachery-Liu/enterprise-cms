package com.zachery.cms.common.exception;

import com.zachery.cms.common.api.ApiResponse;
import com.zachery.cms.common.api.FieldViolation;
import com.zachery.cms.common.api.ValidationErrorData;
import com.zachery.cms.common.context.RequestIdContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.HttpMethod;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterEach
    void clearRequestContext() {
        RequestIdContext.clear();
    }

    @Test
    void businessExceptionPreservesHttpStatusAndStableCode() {
        RequestIdContext.setRequestId("r-1");

        var response = handler.handleBusinessException(
                new BusinessException(HttpStatus.CONFLICT, "ARTICLE_STATUS_CONFLICT", "状态不允许"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("ARTICLE_STATUS_CONFLICT", response.getBody().code());
        assertEquals("r-1", response.getBody().requestId());
        assertNull(response.getBody().data());
    }

    @Test
    void validationErrorUsesContractCodeAndStructuredData() {
        BindException exception = new BindException(new Object(), "input");
        exception.addError(new FieldError("input", "username", "用户名不能为空"));
        var response = handler.handleFieldValidation(exception);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        ApiResponse<ValidationErrorData> body = response.getBody();
        assertEquals("COMMON_VALIDATION_ERROR", body.code());
        assertEquals(new FieldViolation("username", "用户名不能为空"), body.data().errors().get(0));
    }

    @Test
    void unexpectedFailureDoesNotExposeInternalMessage() {
        var response = handler.handleUnexpectedException(new IllegalStateException("secret SQL and path"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("COMMON_INTERNAL_ERROR", response.getBody().code());
        assertFalse(response.getBody().message().contains("secret"));
    }

    @Test
    void missingRouteUsesNotFoundContract() {
        var response = handler.handleNotFound(new NoResourceFoundException(HttpMethod.GET, "/missing"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("COMMON_NOT_FOUND", response.getBody().code());
    }
}
