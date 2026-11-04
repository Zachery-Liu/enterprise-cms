package com.zachery.cms.modules.identity.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter
@TableName("sys_user")
public class UserEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private String username;
    @JsonIgnore private String passwordHash;
    private String displayName;
    private String status;
    private LocalDateTime passwordChangedAt;
    @Version private Integer version;
    @TableLogic(value = "0", delval = "1") private Integer deleted;
    @TableField(fill = FieldFill.INSERT) private Long createdBy;
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE) private Long updatedBy;
    @TableField(fill = FieldFill.INSERT_UPDATE) private LocalDateTime updatedAt;
}
