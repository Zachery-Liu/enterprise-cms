package com.zachery.cms.modules.content.article.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter @TableName("cms_article")
public class ArticleEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private Long categoryId;
    private Long authorId;
    private String title;
    private String summary;
    private String content;
    private String status;
    private String reviewComment;
    private Long reviewerId;
    private LocalDateTime reviewedAt;
    private LocalDateTime publishedAt;
    @Version private Integer version;
    @TableLogic(value = "0", delval = "1") private Integer deleted;
    @TableField(fill = FieldFill.INSERT) private Long createdBy;
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE) private Long updatedBy;
    @TableField(fill = FieldFill.INSERT_UPDATE) private LocalDateTime updatedAt;
}
