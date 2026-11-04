package com.zachery.cms.modules.identity.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter
@TableName("sys_user_role")
public class UserRoleEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private Long userId;
    private Long roleId;
    @TableField(fill = FieldFill.INSERT) private Long createdBy;
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createdAt;
}
