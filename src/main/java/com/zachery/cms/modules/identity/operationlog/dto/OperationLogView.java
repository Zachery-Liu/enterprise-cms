package com.zachery.cms.modules.identity.operationlog.dto;

import com.zachery.cms.modules.identity.persistence.entity.OperationLogEntity;
import java.time.*;

public record OperationLogView(Long id, Long operatorId, String operatorName, String module,
        String action, String targetType, Long targetId, String result, String summary,
        String errorCode, String requestId, String ipAddress, Instant createdAt) {
    public static OperationLogView from(OperationLogEntity row) {
        return new OperationLogView(row.getId(), row.getOperatorId(), row.getOperatorName(),
                row.getModule(), row.getAction(), row.getTargetType(), row.getTargetId(),
                row.getResult(), row.getSummary(), row.getErrorCode(), row.getRequestId(),
                row.getIpAddress(), row.getCreatedAt().toInstant(ZoneOffset.UTC));
    }
}
