package com.zachery.cms.modules.identity.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zachery.cms.common.exception.BusinessException;
import com.zachery.cms.modules.identity.auth.dto.*;
import com.zachery.cms.modules.identity.persistence.entity.UserEntity;
import com.zachery.cms.modules.identity.persistence.mapper.UserMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import java.util.*;

@Service
@Validated
public class LoginService {
    private final UserMapper users;
    private final PasswordHasher passwords;
    private final String dummyHash;

    public LoginService(UserMapper users, PasswordHasher passwords) {
        this.users = users;
        this.passwords = passwords;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public LoginUser authenticate(@Valid LoginRequest request) {
        UserEntity user = users.selectOne(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, request.username()));
        // Unknown and logically deleted accounts also perform a full password derivation.
        boolean matches = passwords.matches(request.password(), user == null ? dummyHash : user.getPasswordHash());
        if (user == null || !matches || !"ACTIVE".equals(user.getStatus())) throw rejected();
        // Re-read after the costly derivation to reject a concurrent password/status change.
        UserEntity current = users.selectById(user.getId());
        if (current == null || !"ACTIVE".equals(current.getStatus())
                || !Objects.equals(current.getPasswordHash(), user.getPasswordHash())) throw rejected();
        return view(current);
    }

    public LoginUser findActiveUser(Long id) {
        if (id == null || id <= 0) return null;
        UserEntity user = users.selectById(id);
        return user != null && "ACTIVE".equals(user.getStatus()) ? view(user) : null;
    }

    private LoginUser view(UserEntity user) {
        return new LoginUser(user.getId(), user.getUsername(), user.getDisplayName(), user.getStatus());
    }

    private BusinessException rejected() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHENTICATED",
                "账号或密码不正确，或账号不可用");
    }
}
