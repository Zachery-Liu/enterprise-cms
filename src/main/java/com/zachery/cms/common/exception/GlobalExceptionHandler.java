package com.zachery.cms.common.exception;

import com.zachery.cms.common.api.*;
import com.zachery.cms.common.context.RequestContext;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Object> business(BusinessException exception) {
        Object data = exception instanceof RequestValidationException validation ? validation.getData() : null;
        return ResponseEntity.status(exception.getHttpStatus())
                .body(ApiResponse.error(exception.getCode(), exception.getMessage(), data));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> constraint(ConstraintViolationException exception) {
        List<FieldViolation> errors = exception.getConstraintViolations().stream()
                .map(v -> new FieldViolation(v.getPropertyPath().toString(), v.getMessage()))
                .sorted(java.util.Comparator.comparing(FieldViolation::field)).toList();
        return error(ErrorCode.COMMON_VALIDATION_ERROR, new ValidationErrorData(errors));
    }

    @ExceptionHandler({DataIntegrityViolationException.class, OptimisticLockingFailureException.class})
    public ResponseEntity<Object> conflict(Exception exception) {
        return error(ErrorCode.COMMON_CONFLICT, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> unexpected(Exception exception) {
        logType(exception);
        return error(ErrorCode.COMMON_INTERNAL_ERROR, null);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) logType(exception);
        ErrorCode code = switch (status.value()) {
            case 400 -> ErrorCode.COMMON_VALIDATION_ERROR;
            case 401 -> ErrorCode.AUTH_UNAUTHENTICATED;
            case 403 -> ErrorCode.AUTH_FORBIDDEN;
            case 404 -> ErrorCode.COMMON_NOT_FOUND;
            case 409 -> ErrorCode.COMMON_CONFLICT;
            default -> status.is5xxServerError() ? ErrorCode.COMMON_INTERNAL_ERROR : null;
        };
        Object data = null;
        if (exception instanceof MethodArgumentNotValidException validation) {
            data = new ValidationErrorData(validation.getBindingResult().getAllErrors().stream()
                    .map(e -> new FieldViolation(e instanceof org.springframework.validation.FieldError field
                            ? field.getField() : "request", e.getDefaultMessage())).toList());
        } else if (exception instanceof HandlerMethodValidationException validation && !validation.isForReturnValue()) {
            data = new ValidationErrorData(validation.getParameterValidationResults().stream()
                    .flatMap(result -> result.getResolvableErrors().stream().map(e ->
                            new FieldViolation(result.getMethodParameter().getParameterName() == null
                                    ? "request" : result.getMethodParameter().getParameterName(), e.getDefaultMessage())))
                    .toList());
        }
        String businessCode = code == null ? "COMMON_HTTP_ERROR" : code.name();
        String message = code == null ? "请求不符合接口要求" : code.getMessage();
        return super.handleExceptionInternal(exception,
                ApiResponse.error(businessCode, message, data), headers, status, request);
    }

    private ResponseEntity<Object> error(ErrorCode code, Object data) {
        return ResponseEntity.status(code.getHttpStatus())
                .body(ApiResponse.error(code.name(), code.getMessage(), data));
    }

    private void logType(Exception exception) {
        // Deliberately omit raw messages, request bodies and stack traces containing sensitive data.
        LoggerFactory.getLogger(getClass()).error("requestId={} exceptionType={}",
                RequestContext.requestId(), exception.getClass().getName());
    }
}
