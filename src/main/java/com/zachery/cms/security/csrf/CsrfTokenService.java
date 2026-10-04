package com.zachery.cms.security.csrf;

import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;

@Component
public class CsrfTokenService {
    private static final String SESSION_KEY = "CMS_CSRF_TOKEN";
    private final SecureRandom random = new SecureRandom();

    public String getOrCreate(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        synchronized (session) {
            String token = (String) session.getAttribute(SESSION_KEY);
            if (token == null) {
                byte[] bytes = new byte[32];
                random.nextBytes(bytes);
                token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                session.setAttribute(SESSION_KEY, token);
            }
            return token;
        }
    }

    public boolean isValid(HttpServletRequest request) {
        String supplied = request.getHeader("X-CSRF-Token");
        if (supplied == null || !supplied.matches("[A-Za-z0-9_-]{43}")) return false;
        HttpSession session = request.getSession(false);
        if (session == null) return false;
        try {
            String expected = (String) session.getAttribute(SESSION_KEY);
            return expected != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    supplied.getBytes(StandardCharsets.US_ASCII));
        } catch (IllegalStateException expiredSession) {
            return false;
        }
    }

    public String rotate(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) throw new IllegalStateException("Session required for token rotation");
        synchronized (session) {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            session.setAttribute(SESSION_KEY, token);
            return token;
        }
    }
}
