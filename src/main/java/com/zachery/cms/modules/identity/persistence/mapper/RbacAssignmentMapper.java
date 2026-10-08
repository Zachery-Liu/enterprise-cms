package com.zachery.cms.modules.identity.persistence.mapper;

import com.zachery.cms.modules.identity.persistence.projection.AssignmentTarget;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface RbacAssignmentMapper {
    Long lockManagementPermission();
    AssignmentTarget lockUser(@Param("id") long id);
    AssignmentTarget lockRole(@Param("id") long id);
    List<Long> lockEnabledRoles(@Param("ids") List<Long> ids);
    List<Long> lockEnabledPermissions(@Param("ids") List<Long> ids);
    int deleteUserRoles(@Param("id") long id);
    int deleteRolePermissions(@Param("id") long id);
    int insertUserRoles(@Param("id") long id, @Param("ids") List<Long> ids, @Param("actorId") long actorId);
    int insertRolePermissions(@Param("id") long id, @Param("ids") List<Long> ids, @Param("actorId") long actorId);
    boolean hasRbacAdministrator();
}
