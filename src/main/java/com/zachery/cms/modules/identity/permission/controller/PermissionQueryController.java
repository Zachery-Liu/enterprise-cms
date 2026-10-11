package com.zachery.cms.modules.identity.permission.controller;

import com.zachery.cms.common.api.ApiResponse;
import com.zachery.cms.modules.identity.permission.dto.PermissionSummary;
import com.zachery.cms.modules.identity.role.service.RbacQueryService;
import com.zachery.cms.security.annotation.RequirePermission;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/permissions")
public class PermissionQueryController {
    private final RbacQueryService queries;

    public PermissionQueryController(RbacQueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    @RequirePermission("cms:permission:manage")
    public ApiResponse<List<PermissionSummary>> permissions() {
        return ApiResponse.ok(queries.permissions());
    }
}
