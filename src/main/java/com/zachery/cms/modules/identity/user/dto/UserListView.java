package com.zachery.cms.modules.identity.user.dto;

import com.zachery.cms.modules.identity.role.dto.RoleSummary;
import java.time.Instant;
import java.util.List;

/** Management assignments, not the effective permissions of the listed account. */
public record UserListView(Long id, String username, String displayName, String status,
        Integer version, Instant createdAt, Instant updatedAt, List<RoleSummary> roles) {
    public UserListView { roles = List.copyOf(roles); }
    public static UserListView from(UserAdminView user, List<RoleSummary> roles) {
        return new UserListView(user.id(), user.username(), user.displayName(), user.status(),
                user.version(), user.createdAt(), user.updatedAt(), roles);
    }
}
