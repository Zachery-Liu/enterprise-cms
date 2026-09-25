package com.zachery.cms.modules.content.article.dto;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** List projection deliberately excludes the article body. */
@Getter @Setter
public class ArticlePageRow {
    private Long id;
    private String title;
    private String summary;
    private Long categoryId;
    private String categoryName;
    private Long authorId;
    private String status;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime publishedAt;
    private LocalDateTime submittedAt;
}
