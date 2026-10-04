package com.zachery.cms.modules.content.publication;

import java.time.Instant;

public record HistoryVO(Long id,Long articleId,String action,String fromStatus,String toStatus,String reason,
        Long operatorId,String operatorDisplayName,Instant createdAt) { }
