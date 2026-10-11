package com.zachery.cms.logging.aspect;

import com.zachery.cms.common.context.CurrentUserContext;
import com.zachery.cms.logging.annotation.OperationLog;
import com.zachery.cms.logging.service.*;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

@Aspect
@Order(200)
public class OperationLogAspect {
    private final CurrentUserContext users;
    private final OperationLogRecorder logs;
    private final TargetIdResolver ids = new TargetIdResolver();
    private final TransactionTemplate transactions;
    public OperationLogAspect(CurrentUserContext users, OperationLogRecorder logs, PlatformTransactionManager manager) {
        this.users = users; this.logs = logs;
        transactions = new TransactionTemplate(manager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Around(value="execution(public * *(..)) && @annotation(annotation)", argNames="call,annotation")
    public Object record(ProceedingJoinPoint call, OperationLog annotation) throws Throwable {
        var actor = users.getCurrentUser().orElse(null);
        AuditEvent event = AuditEvent.capture(actor == null ? null : actor.userId(),
                actor == null ? null : actor.username(), annotation.module(), annotation.action(), annotation.targetType());
        var method = AopUtils.getMostSpecificMethod(((MethodSignature) call.getSignature()).getMethod(), call.getTarget().getClass());
        Long failureTarget = ids.resolve(annotation.targetIdExpression(), method, call.getArgs(), null, false);
        try {
            return transactions.execute(status -> {
                Attempt attempt = new Attempt();
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int outcome) {
                        if (outcome != STATUS_COMMITTED) {
                            String error = outcome == STATUS_UNKNOWN ? "AUDIT_TRANSACTION_UNKNOWN" : attempt.error;
                            logs.independent(event, failureTarget, "FAILURE", error);
                        }
                    }
                });
                try {
                    Object result = call.proceed();
                    Long target = ids.resolve(annotation.targetIdExpression(), method, call.getArgs(), result, true);
                    attempt.error = "AUDIT_WRITE_FAILED";
                    logs.success(event, target);
                    attempt.error = "AUDIT_TRANSACTION_ROLLED_BACK";
                    return result;
                } catch (Throwable failure) {
                    if (!"AUDIT_WRITE_FAILED".equals(attempt.error)) attempt.error = AuditEvent.errorCode(failure);
                    status.setRollbackOnly();
                    throw new InvocationFailure(failure);
                }
            });
        } catch (InvocationFailure failure) { throw failure.getCause(); }
    }
    private static class Attempt { String error = "AUDIT_TRANSACTION_ROLLED_BACK"; }
    private static class InvocationFailure extends RuntimeException {
        InvocationFailure(Throwable cause) { super(null, cause, false, false); }
    }
}
