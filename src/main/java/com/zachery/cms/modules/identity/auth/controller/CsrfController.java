package com.zachery.cms.modules.identity.auth.controller;

import com.zachery.cms.common.api.ApiResponse;
import com.zachery.cms.security.csrf.CsrfTokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class CsrfController {
    public record CsrfToken(String token) {}
    private final CsrfTokenService tokens;

    public CsrfController(CsrfTokenService tokens) { this.tokens = tokens; }

    @GetMapping("/csrf-token")
    public ResponseEntity<ApiResponse<CsrfToken>> token(HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(new CsrfToken(tokens.getOrCreate(request))));
    }
}
