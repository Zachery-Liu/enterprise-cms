package com.zachery.cms.modules.identity.role.controller;

import com.zachery.cms.common.api.ApiResponse;
import com.zachery.cms.common.validation.RequestValidation;
import com.zachery.cms.modules.identity.role.dto.RoleSummary;
import com.zachery.cms.modules.identity.permission.dto.PermissionSummary;
import com.zachery.cms.modules.identity.role.service.RbacQueryService;
import com.zachery.cms.security.authorization.PermissionQueryGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/roles")
public class RoleQueryController {
    private final RbacQueryService queries;
    private final PermissionQueryGuard access;

    public RoleQueryController(RbacQueryService queries, PermissionQueryGuard access) {
        this.queries = queries;
        this.access = access;
    }

    @GetMapping
    public ApiResponse<List<RoleSummary>> roles(HttpServletRequest request) {
        access.require(request, "cms:role:assign");
        return ApiResponse.ok(queries.roles());
    }

    @GetMapping("/{id}")
    public ApiResponse<RoleSummary> role(@PathVariable String id, HttpServletRequest request) {
        access.require(request, "cms:role:assign");
        return ApiResponse.ok(queries.role(RequestValidation.positiveId(id, "id")));
    }

    @GetMapping("/{id}/permissions")
    public ApiResponse<List<PermissionSummary>> permissions(@PathVariable String id, HttpServletRequest request) {
        access.require(request, "cms:permission:manage");
        return ApiResponse.ok(queries.rolePermissions(RequestValidation.positiveId(id, "id")));
    }
}
