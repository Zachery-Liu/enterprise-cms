package com.zachery.cms.modules.identity.permission.controller;

import com.zachery.cms.common.api.ApiResponse;
import com.zachery.cms.modules.identity.permission.dto.PermissionSummary;
import com.zachery.cms.modules.identity.role.service.RbacQueryService;
import com.zachery.cms.security.authorization.PermissionQueryGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/permissions")
public class PermissionQueryController {
    private final RbacQueryService queries;
    private final PermissionQueryGuard access;

    public PermissionQueryController(RbacQueryService queries, PermissionQueryGuard access) {
        this.queries = queries;
        this.access = access;
    }

    @GetMapping
    public ApiResponse<List<PermissionSummary>> permissions(HttpServletRequest request) {
        access.require(request, "cms:permission:manage");
        return ApiResponse.ok(queries.permissions());
    }
}
