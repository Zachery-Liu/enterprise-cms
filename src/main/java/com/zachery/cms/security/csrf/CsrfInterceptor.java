package com.zachery.cms.security.csrf;

import com.zachery.cms.common.exception.BusinessException;
import jakarta.servlet.http.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import java.util.Set;

@Component
public class CsrfInterceptor implements HandlerInterceptor {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private final CsrfTokenService tokens;

    public CsrfInterceptor(CsrfTokenService tokens) { this.tokens = tokens; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!SAFE_METHODS.contains(request.getMethod()) && !tokens.isValid(request))
            throw new BusinessException(HttpStatus.FORBIDDEN, "AUTH_FORBIDDEN", "请求校验失败，请重新获取 CSRF 令牌");
        return true;
    }
}
