package com.zachery.cms.security.session;

import com.zachery.cms.modules.identity.auth.dto.LoginUser;
import jakarta.servlet.http.*;
import org.springframework.web.context.request.*;
import java.util.Optional;

/** Reads only the principal established by the authentication interceptor for this request. */
final class SessionRequestUser {
    private SessionRequestUser() {}

    static HttpServletRequest request() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest() : null;
    }

    static Optional<LoginUser> current() {
        HttpServletRequest request = request();
        if (request == null || !(request.getAttribute(SessionKeys.REQUEST_USER) instanceof LoginUser user))
            return Optional.empty();
        HttpSession session = request.getSession(false);
        try {
            return session != null && user.userId().equals(session.getAttribute(SessionKeys.CURRENT_USER_ID))
                    ? Optional.of(user) : Optional.empty();
        } catch (IllegalStateException expired) {
            return Optional.empty();
        }
    }
}
