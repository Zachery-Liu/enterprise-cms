package com.zachery.cms.modules.identity.role.service;

import com.zachery.cms.common.exception.*;
import com.zachery.cms.common.validation.RequestValidation;
import com.zachery.cms.modules.identity.role.dto.RoleSummary;
import com.zachery.cms.modules.identity.permission.dto.PermissionSummary;
import com.zachery.cms.modules.identity.persistence.mapper.*;
import com.zachery.cms.modules.identity.persistence.projection.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class RbacQueryService {
    public static final int MAX_BATCH_SIZE = 500;
    private final RbacQueryMapper queries;
    private final PermissionMapper permissions;

    public RbacQueryService(RbacQueryMapper queries, PermissionMapper permissions) {
        this.queries = queries;
        this.permissions = permissions;
    }

    public List<RoleSummary> roles() { return List.copyOf(queries.selectRoles()); }
    public List<PermissionSummary> permissions() { return List.copyOf(queries.selectPermissions()); }

    public RoleSummary role(long id) {
        positiveId(id, "roleId");
        RoleSummary role = queries.selectRole(id);
        if (role == null) throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        return role;
    }

    public Map<Long, List<RoleSummary>> rolesForUsers(Collection<Long> userIds) {
        List<Long> ids = RequestValidation.ids(userIds, "userIds", MAX_BATCH_SIZE, true);
        if (ids.isEmpty()) return Map.of();
        Map<Long, List<RoleSummary>> result = emptyGroups(ids);
        for (UserRoleRow row : queries.selectRolesForUsers(ids)) result.get(row.userId()).add(row.role());
        return freeze(result);
    }

    public Map<Long, List<PermissionSummary>> permissionsForRoles(Collection<Long> roleIds) {
        List<Long> ids = RequestValidation.ids(roleIds, "roleIds", MAX_BATCH_SIZE, true);
        if (ids.isEmpty()) return Map.of();
        Map<Long, List<PermissionSummary>> result = emptyGroups(ids);
        for (RolePermissionRow row : queries.selectPermissionsForRoles(ids)) result.get(row.roleId()).add(row.permission());
        return freeze(result);
    }

    public List<PermissionSummary> rolePermissions(long id) {
        role(id); // Management lookup distinguishes a nonexistent/deleted role from an empty grant set.
        return permissionsForRoles(List.of(id)).get(id);
    }

    public List<String> effectivePermissionCodes(long userId) {
        positiveId(userId, "userId");
        return List.copyOf(permissions.selectEffectivePermissionCodes(userId));
    }

    private static void positiveId(long id, String field) {
        if (id <= 0) throw new RequestValidationException(field, "ID 必须为正整数");
    }

    private static <T> Map<Long, List<T>> emptyGroups(List<Long> ids) {
        Map<Long, List<T>> groups = new LinkedHashMap<>();
        ids.forEach(id -> groups.put(id, new ArrayList<>()));
        return groups;
    }

    private static <T> Map<Long, List<T>> freeze(Map<Long, List<T>> groups) {
        groups.replaceAll((id, values) -> List.copyOf(values));
        return Collections.unmodifiableMap(groups);
    }
}
