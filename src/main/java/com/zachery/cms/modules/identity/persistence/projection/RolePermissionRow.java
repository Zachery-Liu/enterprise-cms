package com.zachery.cms.modules.identity.persistence.projection;

import com.zachery.cms.modules.identity.permission.dto.PermissionSummary;

public record RolePermissionRow(Long roleId, Long permissionId, String code, String name, String description, String status) {
    public PermissionSummary permission() { return new PermissionSummary(permissionId, code, name, description, status); }
}
