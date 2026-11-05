package com.zachery.cms.modules.content.article.vo;

import java.time.Instant;

public record ArticleDetailVO(Long id, String title, String summary, String content,
        Long categoryId, String categoryName, Long authorId, String authorDisplayName,
        String status, Integer version, Instant createdAt, Instant updatedAt, Instant publishedAt,
        Long reviewerId, String reviewerDisplayName, String reviewComment, Instant reviewedAt) { }
