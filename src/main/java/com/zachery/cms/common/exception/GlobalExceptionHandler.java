package com.zachery.cms.common.exception;

import com.zachery.cms.common.api.ApiResponse;
import com.zachery.cms.common.api.FieldViolation;
import com.zachery.cms.common.api.ValidationErrorData;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        return ResponseEntity.status(exception.getHttpStatus())
                .body(ApiResponse.failure(exception.getCode(), safeMessage(exception.getMessage())));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<ApiResponse<ValidationErrorData>> handleFieldValidation(Exception exception) {
        List<FieldError> fieldErrors = exception instanceof MethodArgumentNotValidException invalid
                ? invalid.getBindingResult().getFieldErrors()
                : ((BindException) exception).getBindingResult().getFieldErrors();
        List<FieldViolation> violations = fieldErrors.stream()
                .map(error -> new FieldViolation(error.getField(), safeMessage(error.getDefaultMessage())))
                .sorted(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message))
                .toList();
        return validationResponse(violations);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<ValidationErrorData>> handleConstraintViolation(
            ConstraintViolationException exception) {
        List<FieldViolation> violations = exception.getConstraintViolations().stream()
                .map(this::toViolation)
                .sorted(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message))
                .toList();
        return validationResponse(violations);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<ValidationErrorData>> handleMalformedRequest(Exception exception) {
        return validationResponse(List.of(new FieldViolation("request", "请求参数格式错误")));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NoResourceFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.failure("COMMON_NOT_FOUND", "请求的资源不存在"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatus(ResponseStatusException exception) {
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
        if (status != HttpStatus.BAD_REQUEST
                && status != HttpStatus.UNAUTHORIZED
                && status != HttpStatus.FORBIDDEN
                && status != HttpStatus.NOT_FOUND
                && status != HttpStatus.CONFLICT) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.failure("COMMON_INTERNAL_ERROR", "服务器内部错误"));
        }
        String code = switch (status) {
            case BAD_REQUEST -> "COMMON_VALIDATION_ERROR";
            case UNAUTHORIZED -> "AUTH_UNAUTHENTICATED";
            case FORBIDDEN -> "AUTH_FORBIDDEN";
            case NOT_FOUND -> "COMMON_NOT_FOUND";
            case CONFLICT -> "COMMON_CONFLICT";
            default -> "COMMON_INTERNAL_ERROR";
        };
        String message = switch (status) {
            case BAD_REQUEST -> "请求参数校验失败";
            case UNAUTHORIZED -> "请先登录";
            case FORBIDDEN -> "当前用户无权执行此操作";
            case NOT_FOUND -> "请求的资源不存在";
            case CONFLICT -> "请求与当前数据状态冲突";
            default -> "服务器内部错误";
        };
        return ResponseEntity.status(status).body(ApiResponse.failure(code, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
        // Keep internal details in server logs only; never return exception text or stack traces.
        log.error("Unhandled request failure, requestId={}",
                com.zachery.cms.common.context.RequestIdContext.getRequestId(), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failure("COMMON_INTERNAL_ERROR", "服务器内部错误"));
    }

    private FieldViolation toViolation(ConstraintViolation<?> violation) {
        return new FieldViolation(violation.getPropertyPath().toString(), safeMessage(violation.getMessage()));
    }

    private ResponseEntity<ApiResponse<ValidationErrorData>> validationResponse(List<FieldViolation> violations) {
        return ResponseEntity.badRequest().body(ApiResponse.failure(
                "COMMON_VALIDATION_ERROR", "请求参数校验失败", new ValidationErrorData(violations)));
    }

    private String safeMessage(String message) {
        return message == null || message.isBlank() ? "请求处理失败" : message;
    }
}
