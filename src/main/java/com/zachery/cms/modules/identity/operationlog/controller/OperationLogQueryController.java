package com.zachery.cms.modules.identity.operationlog.controller;

import com.zachery.cms.common.api.*;
import com.zachery.cms.common.exception.RequestValidationException;
import com.zachery.cms.modules.identity.operationlog.dto.*;
import com.zachery.cms.modules.identity.operationlog.service.OperationLogQueryService;
import com.zachery.cms.security.annotation.RequirePermission;
import org.springframework.http.*;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.util.Set;

@RestController
@RequestMapping("/api/operation-logs")
public class OperationLogQueryController {
    private static final Set<String> PARAMETERS = Set.of("page", "size", "operatorId",
            "operatorName", "module", "action", "result", "from", "to");
    private final OperationLogQueryService queries;
    public OperationLogQueryController(OperationLogQueryService queries) { this.queries = queries; }

    @GetMapping
    @RequirePermission("cms:log:read")
    public ResponseEntity<ApiResponse<PageResponse<OperationLogView>>> query(
            @RequestParam MultiValueMap<String, String> parameters) {
        if (parameters.entrySet().stream().anyMatch(entry ->
                !PARAMETERS.contains(entry.getKey()) || entry.getValue().size() != 1))
            throw new RequestValidationException("query", "存在未知参数或重复参数");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(queries.query(OperationLogQuery.parse(parameters.toSingleValueMap()))));
    }
}
