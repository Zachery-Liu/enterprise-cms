package com.zachery.cms.modules.identity.role.controller;

import com.zachery.cms.common.api.ApiResponse;
import com.zachery.cms.common.validation.RequestValidation;
import com.zachery.cms.modules.identity.role.dto.RoleSummary;
import com.zachery.cms.modules.identity.permission.dto.PermissionSummary;
import com.zachery.cms.modules.identity.role.service.RbacQueryService;
import com.zachery.cms.security.annotation.RequirePermission;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/roles")
public class RoleQueryController {
    private final RbacQueryService queries;

    public RoleQueryController(RbacQueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    @RequirePermission("cms:role:assign")
    public ApiResponse<List<RoleSummary>> roles() {
        return ApiResponse.ok(queries.roles());
    }

    @GetMapping("/{id}")
    @RequirePermission("cms:role:assign")
    public ApiResponse<RoleSummary> role(@PathVariable String id) {
        return ApiResponse.ok(queries.role(RequestValidation.positiveId(id, "id")));
    }

    @GetMapping("/{id}/permissions")
    @RequirePermission("cms:permission:manage")
    public ApiResponse<List<PermissionSummary>> permissions(@PathVariable String id) {
        return ApiResponse.ok(queries.rolePermissions(RequestValidation.positiveId(id, "id")));
    }
}
