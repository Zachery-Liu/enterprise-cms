package com.zachery.cms.modules.identity.role.service;

import com.zachery.cms.common.context.CurrentUserContext;
import com.zachery.cms.common.exception.*;
import com.zachery.cms.common.validation.RequestValidation;
import com.zachery.cms.modules.identity.persistence.mapper.*;
import com.zachery.cms.modules.identity.persistence.projection.AssignmentTarget;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class RbacAssignmentService {
    public static final int MAX_BATCH_SIZE = 500;
    private final RbacAssignmentMapper assignments;
    private final PermissionMapper permissions;
    private final CurrentUserContext users;

    public RbacAssignmentService(RbacAssignmentMapper assignments, PermissionMapper permissions, CurrentUserContext users) {
        this.assignments = assignments;
        this.permissions = permissions;
        this.users = users;
    }

    public void setUserRoles(long userId, Collection<Long> roleIds) {
        long actorId = authorize("cms:role:assign");
        positiveId(userId, "userId");
        List<Long> ids = RequestValidation.ids(roleIds, "roleIds", MAX_BATCH_SIZE, true);
        requireEnabled(assignments.lockUser(userId), "ACTIVE");
        if (!ids.isEmpty() && assignments.lockEnabledRoles(ids).size() != ids.size())
            throw new RequestValidationException("roleIds", "角色必须存在且处于启用状态");
        assignments.deleteUserRoles(userId);
        if (!ids.isEmpty()) assignments.insertUserRoles(userId, ids, actorId);
        requireAdministrator();
    }

    public void setRolePermissions(long roleId, Collection<Long> permissionIds) {
        long actorId = authorize("cms:permission:manage");
        positiveId(roleId, "roleId");
        List<Long> ids = RequestValidation.ids(permissionIds, "permissionIds", MAX_BATCH_SIZE, true);
        requireEnabled(assignments.lockRole(roleId), "ENABLED");
        if (!ids.isEmpty() && assignments.lockEnabledPermissions(ids).size() != ids.size())
            throw new RequestValidationException("permissionIds", "权限必须存在且处于启用状态");
        assignments.deleteRolePermissions(roleId);
        if (!ids.isEmpty()) assignments.insertRolePermissions(roleId, ids, actorId);
        requireAdministrator();
    }

    private long authorize(String requiredPermission) {
        long actorId = users.getRequiredUser().userId();
        // Serialize both kinds of assignment before checking live authority or last-administrator state.
        if (assignments.lockManagementPermission() == null)
            throw new BusinessException(HttpStatus.CONFLICT, "COMMON_CONFLICT", "管理权限初始化数据缺失");
        AssignmentTarget actor = assignments.lockUser(actorId);
        if (actor == null || !"ACTIVE".equals(actor.status())) throw new BusinessException(ErrorCode.AUTH_UNAUTHENTICATED);
        // A request may wait while another assignment revokes its authority. Do not use the cached snapshot here.
        if (!permissions.selectEffectivePermissionCodes(actorId).contains(requiredPermission))
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        return actorId;
    }

    private void requireAdministrator() {
        if (!assignments.hasRbacAdministrator())
            throw new BusinessException(HttpStatus.CONFLICT, "COMMON_CONFLICT", "必须保留至少一个同时具备角色分配和权限配置能力的有效账号");
    }

    private static void positiveId(long id, String field) {
        if (id <= 0) throw new RequestValidationException(field, "ID 必须为正整数");
    }

    private static void requireEnabled(AssignmentTarget target, String enabledStatus) {
        if (target == null) throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        if (!enabledStatus.equals(target.status()))
            throw new BusinessException(HttpStatus.CONFLICT, "COMMON_CONFLICT", "目标已停用，不能修改授权");
    }
}
