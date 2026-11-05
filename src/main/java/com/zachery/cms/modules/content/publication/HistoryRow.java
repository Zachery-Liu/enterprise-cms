package com.zachery.cms.modules.content.publication;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter
public class HistoryRow {
    private Long id;
    private Long articleId;
    private String action;
    private String fromStatus;
    private String toStatus;
    private String reason;
    private Long operatorId;
    private LocalDateTime createdAt;
}
