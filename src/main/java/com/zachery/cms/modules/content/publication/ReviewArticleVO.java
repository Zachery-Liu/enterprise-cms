package com.zachery.cms.modules.content.publication;

import java.time.Instant;

public record ReviewArticleVO(Long id,String title,String summary,Long categoryId,String categoryName,
        Long authorId,String authorDisplayName,String status,Integer version,Instant createdAt,Instant updatedAt,
        Instant publishedAt,Instant submittedAt) { }
