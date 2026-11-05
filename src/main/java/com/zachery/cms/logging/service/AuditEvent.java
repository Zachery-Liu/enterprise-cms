package com.zachery.cms.logging.service;

import com.zachery.cms.common.context.RequestContext;
import com.zachery.cms.common.exception.BusinessException;
import org.springframework.web.context.request.*;
import java.time.*;

/** Immutable metadata only. Never retains request bodies, exceptions, sessions or credentials. */
public record AuditEvent(Long operatorId, String operatorName, String module, String action,
                         String targetType, String requestId, String ipAddress, LocalDateTime time) {
    public static AuditEvent capture(Long actor, String name, String module, String action, String targetType) {
        if (!code(module) || !code(action) || (!targetType.isEmpty() && !code(targetType)))
            throw new IllegalArgumentException("Audit metadata must use stable uppercase codes");
        String ip = null;
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String remote = attributes.getRequest().getRemoteAddr();
            if (remote != null && remote.matches("[0-9a-fA-F:.]{2,45}")) ip = remote;
        }
        return new AuditEvent(actor, name, module, action, targetType.isEmpty() ? null : targetType,
                RequestContext.requestId(), ip, LocalDateTime.now(ZoneOffset.UTC));
    }
    public static String errorCode(Throwable failure) {
        if (failure instanceof BusinessException business && code(business.getCode())) return business.getCode();
        return "COMMON_INTERNAL_ERROR";
    }
    private static boolean code(String value) { return value != null && value.matches("[A-Z][A-Z0-9_]{0,63}"); }
}
