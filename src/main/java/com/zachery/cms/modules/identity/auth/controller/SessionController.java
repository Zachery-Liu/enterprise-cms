package com.zachery.cms.modules.identity.auth.controller;

import com.zachery.cms.common.api.ApiResponse;
import com.zachery.cms.common.context.RequestContext;
import com.zachery.cms.common.exception.*;
import com.zachery.cms.modules.identity.auth.dto.*;
import com.zachery.cms.modules.identity.auth.service.LoginService;
import com.zachery.cms.security.annotation.AnonymousAccess;
import com.zachery.cms.security.csrf.CsrfTokenService;
import com.zachery.cms.security.session.SessionKeys;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import org.slf4j.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class SessionController {
    private static final Logger LOG = LoggerFactory.getLogger(SessionController.class);
    private final LoginService login;
    private final CsrfTokenService tokens;

    public SessionController(LoginService login, CsrfTokenService tokens) {
        this.login = login;
        this.tokens = tokens;
    }

    @PostMapping("/login")
    @AnonymousAccess
    public ResponseEntity<ApiResponse<LoginUser>> login(@Valid @RequestBody LoginRequest body,
                                                       HttpServletRequest request) {
        LoginUser user;
        try { user = login.authenticate(body); }
        catch (BusinessException rejected) {
            LOG.info("module=AUTH action=LOGIN result=FAILURE requestId={}", RequestContext.requestId());
            throw rejected;
        }
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
        LOG.info("module=AUTH action=LOGIN result=SUCCESS userId={} requestId={}",
                user.userId(), RequestContext.requestId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(user));
    }

    @PostMapping("/logout")
    @AnonymousAccess
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            try { session.invalidate(); } catch (IllegalStateException alreadyExpired) { /* Safe repeat. */ }
        }
        LOG.info("module=AUTH action=LOGOUT result=SUCCESS requestId={}", RequestContext.requestId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok());
    }
}
