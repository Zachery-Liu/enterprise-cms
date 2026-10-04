package com.zachery.cms.security.session;

import com.zachery.cms.common.exception.*;
import com.zachery.cms.modules.identity.auth.dto.LoginUser;
import com.zachery.cms.modules.identity.auth.service.LoginService;
import com.zachery.cms.security.annotation.AnonymousAccess;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class SessionAuthenticationInterceptor implements HandlerInterceptor {
    private final LoginService login;

    public SessionAuthenticationInterceptor(LoginService login) { this.login = login; }

    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.removeAttribute(SessionKeys.REQUEST_USER);
        if (!(handler instanceof HandlerMethod method) || method.hasMethodAnnotation(AnonymousAccess.class)) return true;
        HttpSession session = request.getSession(false);
        if (session == null) throw new BusinessException(ErrorCode.AUTH_UNAUTHENTICATED);
        Object id;
        try { id = session.getAttribute(SessionKeys.CURRENT_USER_ID); }
        catch (IllegalStateException expired) { throw new BusinessException(ErrorCode.AUTH_UNAUTHENTICATED); }
        LoginUser user = id instanceof Long userId ? login.findActiveUser(userId) : null;
        if (user == null) {
            try { session.invalidate(); } catch (IllegalStateException alreadyExpired) { /* Already invalid. */ }
            throw new BusinessException(ErrorCode.AUTH_UNAUTHENTICATED);
        }
        request.setAttribute(SessionKeys.REQUEST_USER, user);
        return true;
    }
}
