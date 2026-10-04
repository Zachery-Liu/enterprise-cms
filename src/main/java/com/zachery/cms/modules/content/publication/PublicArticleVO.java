package com.zachery.cms.modules.content.publication;

import java.time.Instant;

public record PublicArticleVO(Long id,String title,String summary,Long categoryId,String categoryName,
        String authorDisplayName,Instant publishedAt) { }
