package com.zachery.cms.modules.identity.auth.controller;

import com.zachery.cms.common.api.ApiResponse;
import com.zachery.cms.common.context.RequestContext;
import org.slf4j.*;
import com.zachery.cms.logging.service.AuditEvent;
import com.zachery.cms.logging.service.OperationLogRecorder;
import com.zachery.cms.common.exception.*;
import com.zachery.cms.modules.identity.auth.dto.*;
import com.zachery.cms.modules.identity.auth.service.LoginService;
import com.zachery.cms.security.annotation.AnonymousAccess;
import com.zachery.cms.security.csrf.CsrfTokenService;
import com.zachery.cms.security.session.SessionKeys;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class SessionController {
    private static final Logger LOG = LoggerFactory.getLogger(SessionController.class);
    private final LoginService login;
    private final CsrfTokenService tokens;
    private final OperationLogRecorder audit;

    public SessionController(LoginService login, CsrfTokenService tokens, OperationLogRecorder audit) {
        this.login = login;
        this.tokens = tokens;
        this.audit = audit;
    }

    @PostMapping("/login")
    @AnonymousAccess
    public ResponseEntity<ApiResponse<LoginUser>> login(@Valid @RequestBody LoginRequest body,
                                                       HttpServletRequest request) {
        try {
            LoginUser user = authenticateAndRotate(body, request);
            audit.authentication("LOGIN", user.userId(), user.username(), "SUCCESS", null);
            LOG.info("module=AUTH action=LOGIN result=SUCCESS userId={} requestId={}", user.userId(), RequestContext.requestId());
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(user));
        } catch (RuntimeException rejected) {
            audit.authentication("LOGIN", null, null, "FAILURE", AuditEvent.errorCode(rejected));
            LOG.info("module=AUTH action=LOGIN result=FAILURE requestId={}", RequestContext.requestId());
            throw rejected;
        }
    }

    private LoginUser authenticateAndRotate(LoginRequest body, HttpServletRequest request) {
        LoginUser user = login.authenticate(body);
        HttpSession session = request.getSession(false);
        if (session == null) throw new BusinessException(ErrorCode.AUTH_UNAUTHENTICATED);
        try {
            synchronized (session) {
                // Login verification can overlap a logout or another login in the same session.
                if (!tokens.isValid(request)) throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
                request.changeSessionId();
                session.setAttribute(SessionKeys.CURRENT_USER_ID, user.userId());
                tokens.rotate(request);
            }
        } catch (IllegalStateException expired) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHENTICATED);
        }
        return user;
    }

    @PostMapping("/logout")
    @AnonymousAccess
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Long actor = null;
        if (session != null) {
            try {
                Object id = session.getAttribute(SessionKeys.CURRENT_USER_ID);
                if (id instanceof Long userId && userId > 0) actor = userId;
                session.invalidate();
            } catch (IllegalStateException alreadyExpired) { /* Safe repeat. */ }
        }
        audit.authentication("LOGOUT", actor, null, "SUCCESS", null);
        LOG.info("module=AUTH action=LOGOUT result=SUCCESS requestId={}", RequestContext.requestId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok());
    }
}
