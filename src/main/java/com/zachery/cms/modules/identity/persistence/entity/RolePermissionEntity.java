package com.zachery.cms.modules.identity.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter
@TableName("sys_role_permission")
public class RolePermissionEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private Long roleId;
    private Long permissionId;
    @TableField(fill = FieldFill.INSERT) private Long createdBy;
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createdAt;
}
