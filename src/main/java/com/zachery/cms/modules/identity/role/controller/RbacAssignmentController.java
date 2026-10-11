package com.zachery.cms.modules.identity.role.controller;

import com.zachery.cms.common.api.ApiResponse;
import com.zachery.cms.common.validation.RequestValidation;
import com.zachery.cms.modules.identity.role.dto.SetUserRolesRequest;
import com.zachery.cms.modules.identity.permission.dto.SetRolePermissionsRequest;
import com.zachery.cms.modules.identity.role.service.RbacAssignmentService;
import com.zachery.cms.security.annotation.RequirePermission;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class RbacAssignmentController {
    private final RbacAssignmentService assignments;

    public RbacAssignmentController(RbacAssignmentService assignments) {
        this.assignments = assignments;
    }

    @PutMapping("/users/{id}/roles")
    @RequirePermission("cms:role:assign")
    public ApiResponse<Void> roles(@PathVariable String id, @RequestBody SetUserRolesRequest body) {
        assignments.setUserRoles(RequestValidation.positiveId(id, "id"), body.roleIds());
        return ApiResponse.ok();
    }

    @PutMapping("/roles/{id}/permissions")
    @RequirePermission("cms:permission:manage")
    public ApiResponse<Void> permissions(@PathVariable String id, @RequestBody SetRolePermissionsRequest body) {
        assignments.setRolePermissions(RequestValidation.positiveId(id, "id"), body.permissionIds());
        return ApiResponse.ok();
    }
}
