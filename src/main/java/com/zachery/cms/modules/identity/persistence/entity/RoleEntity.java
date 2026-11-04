package com.zachery.cms.modules.identity.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter
@TableName("sys_role")
public class RoleEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private String code;
    private String name;
    private String description;
    private String status;
    private Integer builtIn;
    @Version private Integer version;
    @TableLogic(value = "0", delval = "1") private Integer deleted;
    @TableField(fill = FieldFill.INSERT) private Long createdBy;
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE) private Long updatedBy;
    @TableField(fill = FieldFill.INSERT_UPDATE) private LocalDateTime updatedAt;
}
