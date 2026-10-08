package com.zachery.cms.modules.identity.persistence.mapper;

import com.zachery.cms.modules.identity.role.dto.RoleSummary;
import com.zachery.cms.modules.identity.permission.dto.PermissionSummary;
import com.zachery.cms.modules.identity.persistence.projection.*;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface RbacQueryMapper {
    List<RoleSummary> selectRoles();
    RoleSummary selectRole(@Param("id") long id);
    List<PermissionSummary> selectPermissions();
    List<UserRoleRow> selectRolesForUsers(@Param("userIds") List<Long> userIds);
    List<RolePermissionRow> selectPermissionsForRoles(@Param("roleIds") List<Long> roleIds);
}
