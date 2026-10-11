package com.zachery.cms.security.aspect;

import com.zachery.cms.common.context.*;
import com.zachery.cms.common.exception.*;
import com.zachery.cms.security.annotation.RequirePermission;
import com.zachery.cms.security.session.SessionKeys;
import org.aspectj.lang.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.web.context.request.*;

/** Registered explicitly in the MVC context, where controller proxies are created. */
@Aspect
@Order(100)
public class PermissionAspect {
    private final CurrentUserContext users;

    public PermissionAspect(CurrentUserContext users) { this.users = users; }

    @Before(value = "execution(public * *(..)) && @annotation(permission)", argNames = "permission")
    public void authorize(RequirePermission permission) {
        // The authentication interceptor checks the live account first; B-10 supplies a request-only snapshot.
        CurrentUser user = users.getRequiredUser();
        if (user.status() != UserStatus.ACTIVE) {
            invalidateSession();
            throw new BusinessException(ErrorCode.AUTH_UNAUTHENTICATED);
        }
        // ADMIN is a role, not a bypass; unknown/empty permission codes fail closed.
        if (permission.value().isBlank() || !user.permissionCodes().contains(permission.value()))
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }

    private static void invalidateSession() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            var request = attributes.getRequest();
            request.removeAttribute(SessionKeys.REQUEST_USER);
            request.removeAttribute(SessionKeys.CURRENT_USER_SNAPSHOT);
            var session = request.getSession(false);
            if (session != null) {
                try { session.invalidate(); }
                catch (IllegalStateException alreadyExpired) { /* Already invalid. */ }
            }
        }
    }
}
