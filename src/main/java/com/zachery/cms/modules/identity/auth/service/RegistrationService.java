package com.zachery.cms.modules.identity.auth.service;

import com.zachery.cms.common.exception.*;
import com.zachery.cms.modules.identity.auth.dto.*;
import com.zachery.cms.modules.identity.persistence.entity.*;
import com.zachery.cms.modules.identity.persistence.mapper.*;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import java.time.*;

@Service
@Validated
public class RegistrationService {
    private final UserMapper users;
    private final RoleMapper roles;
    private final UserRoleMapper userRoles;
    private final PasswordHasher passwords;
    private final String defaultRoleCode;

    public RegistrationService(UserMapper users, RoleMapper roles, UserRoleMapper userRoles,
                               PasswordHasher passwords,
                               @Value("${cms.registration.default-role-code:USER}") String defaultRoleCode) {
        this.users = users;
        this.roles = roles;
        this.userRoles = userRoles;
        this.passwords = passwords;
        this.defaultRoleCode = defaultRoleCode;
    }

    @Transactional
    public RegisteredUser register(@Valid RegisterRequest request) {
        // Hash before acquiring the shared default-role row lock.
        String hash = passwords.encode(request.password());
        RoleEntity role = roles.selectRegistrationRoleForUpdate(defaultRoleCode);
        if (role == null) throw new BusinessException(HttpStatus.CONFLICT, "COMMON_CONFLICT",
                "注册暂不可用：默认角色不存在或已停用");

        UserEntity user = new UserEntity();
        user.setUsername(request.username());
        user.setPasswordHash(hash);
        user.setDisplayName(request.displayName());
        user.setStatus("ACTIVE");
        user.setPasswordChangedAt(LocalDateTime.now(ZoneOffset.UTC));
        user.setVersion(0);
        user.setDeleted(0);
        // Do not pre-check uniqueness through a logical-delete-aware query.
        // The database also reserves names of deleted users and resolves races.
        users.insert(user);

        UserRoleEntity relation = new UserRoleEntity();
        relation.setUserId(user.getId());
        relation.setRoleId(role.getId());
        relation.setCreatedBy(user.getId());
        userRoles.insert(relation);

        return new RegisteredUser(user.getId(), user.getUsername(), user.getDisplayName(),
                user.getStatus(), user.getCreatedAt().toInstant(ZoneOffset.UTC));
    }
}
