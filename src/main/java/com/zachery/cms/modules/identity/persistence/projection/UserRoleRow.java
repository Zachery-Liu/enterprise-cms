package com.zachery.cms.modules.identity.persistence.projection;

import com.zachery.cms.modules.identity.role.dto.RoleSummary;

public record UserRoleRow(Long userId, Long roleId, String code, String name, String description, String status) {
    public RoleSummary role() { return new RoleSummary(roleId, code, name, description, status); }
}
