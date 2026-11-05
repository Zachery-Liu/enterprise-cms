package com.zachery.cms.modules.identity.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zachery.cms.modules.identity.persistence.entity.RoleEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface RoleMapper extends BaseMapper<RoleEntity> {
    @Select("SELECT id, code, status FROM sys_role WHERE code = #{code} AND deleted = 0 AND status = 'ENABLED' FOR UPDATE")
    RoleEntity selectRegistrationRoleForUpdate(@Param("code") String code);
}
