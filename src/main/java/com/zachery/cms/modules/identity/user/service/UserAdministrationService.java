package com.zachery.cms.modules.identity.user.service;

import com.zachery.cms.common.context.CurrentUserContext;
import com.zachery.cms.common.exception.*;
import com.zachery.cms.logging.annotation.OperationLog;
import com.zachery.cms.modules.identity.persistence.entity.UserEntity;
import com.zachery.cms.modules.identity.persistence.mapper.*;
import com.zachery.cms.modules.identity.user.dto.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.Objects;

@Service
public class UserAdministrationService {
    private final UserMapper users;
    private final RbacAssignmentMapper assignments;
    private final PermissionMapper permissions;
    private final CurrentUserContext current;
    public UserAdministrationService(UserMapper users, RbacAssignmentMapper assignments,
            PermissionMapper permissions, CurrentUserContext current) {
        this.users=users; this.assignments=assignments; this.permissions=permissions; this.current=current;
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    @OperationLog(module="USER", action="SET_STATUS", targetType="USER", targetIdExpression="#userId")
    public UserAdminView setStatus(long userId, SetUserStatusRequest request) {
        if (userId<=0) throw new RequestValidationException("id","ID 必须为正整数");
        if (request == null || !("ACTIVE".equals(request.status()) || "DISABLED".equals(request.status())))
            throw new RequestValidationException("status","状态必须为 ACTIVE 或 DISABLED");
        if (request.version()==null || request.version()<0)
            throw new RequestValidationException("version","必须提供非负版本号");
        long actorId=current.getRequiredUser().userId();
        // Same first lock as B-13: permission edits and status changes cannot jointly remove the last administrator.
        if (assignments.lockManagementPermission()==null) throw new BusinessException(ErrorCode.COMMON_CONFLICT);
        var actor=assignments.lockUser(actorId);
        if (actor==null || !"ACTIVE".equals(actor.status())) throw new BusinessException(ErrorCode.AUTH_UNAUTHENTICATED);
        if (!permissions.selectEffectivePermissionCodes(actorId).contains("cms:user:manage"))
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        if (assignments.lockUser(userId)==null) throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        UserEntity user=users.selectById(userId);
        if (!Objects.equals(user.getVersion(),request.version())) throw new BusinessException(ErrorCode.COMMON_CONFLICT);
        if (actorId==userId && "DISABLED".equals(request.status()))
            throw new BusinessException(org.springframework.http.HttpStatus.CONFLICT,"COMMON_CONFLICT","不能停用自己的账号");
        if (user.getStatus().equals(request.status())) return UserAdminView.from(user);
        if (user.getVersion()==Integer.MAX_VALUE) throw new BusinessException(ErrorCode.COMMON_CONFLICT);
        UserEntity change=new UserEntity();
        change.setId(userId); change.setStatus(request.status()); change.setVersion(request.version());
        if (users.updateById(change)!=1) throw new BusinessException(ErrorCode.COMMON_CONFLICT);
        if ("DISABLED".equals(request.status()) && !assignments.hasRbacAdministrator())
            throw new BusinessException(org.springframework.http.HttpStatus.CONFLICT,"COMMON_CONFLICT",
                    "必须保留至少一个同时具备角色分配和权限配置能力的有效账号");
        return UserAdminView.from(users.selectById(userId));
    }
}
