package com.zachery.cms.modules.content.category.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter
@Setter
@TableName("cms_category")
public class CategoryEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private Long parentId;
    private String name;
    private Integer sortOrder;
    private String status;
    @Version private Integer version;
    @TableLogic(value = "0", delval = "1") private Integer deleted;
    @TableField(fill = FieldFill.INSERT) private Long createdBy;
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE) private Long updatedBy;
    @TableField(fill = FieldFill.INSERT_UPDATE) private LocalDateTime updatedAt;
}
