package com.zachery.cms.logging.service;

import com.zachery.cms.modules.identity.persistence.entity.OperationLogEntity;
import com.zachery.cms.modules.identity.persistence.mapper.OperationLogMapper;
import org.springframework.transaction.annotation.*;

public class OperationLogWriter {
    private final OperationLogMapper logs;
    public OperationLogWriter(OperationLogMapper logs) { this.logs = logs; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void success(AuditEvent event, Long targetId) { insert(event, targetId, "SUCCESS", null); }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void independent(AuditEvent event, Long targetId, String result, String errorCode) {
        insert(event, targetId, result, errorCode);
    }

    private void insert(AuditEvent event, Long targetId, String result, String errorCode) {
        OperationLogEntity row = new OperationLogEntity();
        row.setOperatorId(event.operatorId()); row.setOperatorName(event.operatorName());
        row.setModule(event.module()); row.setAction(event.action()); row.setTargetType(event.targetType());
        row.setTargetId(targetId); row.setResult(result); row.setErrorCode(errorCode);
        row.setSummary(event.module()+" / "+event.action()+" / "+result);
        row.setRequestId(event.requestId()); row.setIpAddress(event.ipAddress()); row.setCreatedAt(event.time());
        if (logs.insert(row) != 1) throw new IllegalStateException("Audit insert did not persist a row");
    }
}
