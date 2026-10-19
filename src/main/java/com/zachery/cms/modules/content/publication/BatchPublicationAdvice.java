package com.zachery.cms.modules.content.publication;

import com.zachery.cms.common.api.ApiResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Only A's structured batch failure payload; all other errors retain B's global handling. */
@RestControllerAdvice(assignableTypes=ArticleLifecycleController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BatchPublicationAdvice {
    @ExceptionHandler(BatchPublicationException.class)
    public ResponseEntity<?> batch(BatchPublicationException error) {
        return ResponseEntity.status(error.getHttpStatus()).body(ApiResponse.error(error.getCode(),error.getMessage(),Map.of("failures",error.getFailures())));
    }
}
