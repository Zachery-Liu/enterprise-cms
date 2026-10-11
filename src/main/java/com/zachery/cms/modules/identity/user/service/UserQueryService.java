package com.zachery.cms.modules.identity.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zachery.cms.common.api.PageResponse;
import com.zachery.cms.modules.identity.persistence.entity.UserEntity;
import com.zachery.cms.modules.identity.persistence.mapper.UserMapper;
import com.zachery.cms.modules.identity.user.dto.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class UserQueryService {
    private final UserMapper users;
    public UserQueryService(UserMapper users) { this.users = users; }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public PageResponse<UserAdminView> query(UserQuery filter) {
        var conditions = new LambdaQueryWrapper<UserEntity>()
                .select(UserEntity::getId,UserEntity::getUsername,UserEntity::getDisplayName,UserEntity::getStatus,
                        UserEntity::getVersion,UserEntity::getCreatedAt,UserEntity::getUpdatedAt)
                .apply(filter.username()!=null, "username LIKE {0} ESCAPE '!'", filter.usernamePattern())
                .eq(filter.status()!=null, UserEntity::getStatus, filter.status())
                .ge(filter.from()!=null, UserEntity::getCreatedAt, filter.from())
                .le(filter.to()!=null, UserEntity::getCreatedAt, filter.to())
                .orderByDesc(UserEntity::getCreatedAt,UserEntity::getId);
        var page=users.selectPage(new Page<>(filter.page().page(),filter.page().size()),conditions);
        return new PageResponse<>(page.getRecords().stream().map(UserAdminView::from).toList(),
                page.getTotal(),filter.page().page(),filter.page().size());
    }
}
