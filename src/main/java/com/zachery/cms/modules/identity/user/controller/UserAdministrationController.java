package com.zachery.cms.modules.identity.user.controller;

import com.zachery.cms.common.api.*;
import com.zachery.cms.common.exception.RequestValidationException;
import com.zachery.cms.common.validation.RequestValidation;
import com.zachery.cms.modules.identity.user.dto.*;
import com.zachery.cms.modules.identity.user.service.*;
import com.zachery.cms.security.annotation.RequirePermission;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.util.Set;

@RestController
@RequestMapping("/api/users")
public class UserAdministrationController {
    private static final Set<String> PARAMETERS=Set.of("page","size","username","status","from","to");
    private final UserQueryService queries;
    private final UserAdministrationService administration;
    public UserAdministrationController(UserQueryService queries, UserAdministrationService administration) {
        this.queries=queries; this.administration=administration;
    }
    @GetMapping
    @RequirePermission("cms:user:read")
    public ResponseEntity<ApiResponse<PageResponse<UserAdminView>>> query(@RequestParam MultiValueMap<String,String> parameters) {
        if (parameters.entrySet().stream().anyMatch(e -> !PARAMETERS.contains(e.getKey()) || e.getValue().size()!=1))
            throw new RequestValidationException("query","存在未知参数或重复参数");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(queries.query(UserQuery.parse(parameters.toSingleValueMap()))));
    }
    @PutMapping("/{id}/status")
    @RequirePermission("cms:user:manage")
    public ResponseEntity<ApiResponse<UserAdminView>> status(@PathVariable String id, @Valid @RequestBody SetUserStatusRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(
                administration.setStatus(RequestValidation.positiveId(id,"id"),body)));
    }
}
