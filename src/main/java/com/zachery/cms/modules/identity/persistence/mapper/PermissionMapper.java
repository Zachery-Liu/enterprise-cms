package com.zachery.cms.modules.identity.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zachery.cms.modules.identity.persistence.entity.PermissionEntity;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface PermissionMapper extends BaseMapper<PermissionEntity> {
    List<String> selectEffectivePermissionCodes(@Param("userId") Long userId);
}
