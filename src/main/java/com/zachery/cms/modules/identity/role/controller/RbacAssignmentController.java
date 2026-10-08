package com.zachery.cms.modules.identity.role.controller;

import com.zachery.cms.common.api.ApiResponse;
import com.zachery.cms.common.validation.RequestValidation;
import com.zachery.cms.modules.identity.role.dto.SetUserRolesRequest;
import com.zachery.cms.modules.identity.permission.dto.SetRolePermissionsRequest;
import com.zachery.cms.modules.identity.role.service.RbacAssignmentService;
import com.zachery.cms.security.authorization.PermissionQueryGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class RbacAssignmentController {
    private final RbacAssignmentService assignments;
    private final PermissionQueryGuard access;

    public RbacAssignmentController(RbacAssignmentService assignments, PermissionQueryGuard access) {
        this.assignments = assignments;
        this.access = access;
    }

    @PutMapping("/users/{id}/roles")
    public ApiResponse<Void> roles(@PathVariable String id, @RequestBody SetUserRolesRequest body, HttpServletRequest request) {
        access.require(request, "cms:role:assign");
        assignments.setUserRoles(RequestValidation.positiveId(id, "id"), body.roleIds());
        return ApiResponse.ok();
    }

    @PutMapping("/roles/{id}/permissions")
    public ApiResponse<Void> permissions(@PathVariable String id, @RequestBody SetRolePermissionsRequest body, HttpServletRequest request) {
        access.require(request, "cms:permission:manage");
        assignments.setRolePermissions(RequestValidation.positiveId(id, "id"), body.permissionIds());
        return ApiResponse.ok();
    }
}
