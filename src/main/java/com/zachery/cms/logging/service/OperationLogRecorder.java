package com.zachery.cms.logging.service;

import org.slf4j.LoggerFactory;

/** Best-effort independent path only for rolled-back operations and non-transactional sessions. */
public class OperationLogRecorder {
    private final OperationLogWriter writer;
    public OperationLogRecorder(OperationLogWriter writer) { this.writer = writer; }
    public void success(AuditEvent event, Long target) { writer.success(event, target); }
    public void independent(AuditEvent event, Long target, String result, String code) {
        try { writer.independent(event, target, result, code); }
        catch (RuntimeException unavailable) {
            LoggerFactory.getLogger(getClass()).error("auditPersistenceFailed requestId={} module={} action={} result={}",
                    event.requestId(), event.module(), event.action(), result);
        }
    }
    public void authentication(String action, Long actor, String name, String result, String code) {
        independent(AuditEvent.capture(actor, name, "AUTH", action, "USER"), actor, result, code);
    }
}
