package com.zachery.cms.modules.identity.user.dto;

import com.zachery.cms.modules.identity.persistence.entity.UserEntity;
import java.time.*;

public record UserAdminView(Long id, String username, String displayName, String status,
        Integer version, Instant createdAt, Instant updatedAt) {
    public static UserAdminView from(UserEntity user) {
        return new UserAdminView(user.getId(),user.getUsername(),user.getDisplayName(),user.getStatus(),
                user.getVersion(),user.getCreatedAt().toInstant(ZoneOffset.UTC),user.getUpdatedAt().toInstant(ZoneOffset.UTC));
    }
}
