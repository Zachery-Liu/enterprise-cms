package com.zachery.cms.modules.identity.auth.controller;

import com.zachery.cms.common.api.ApiResponse;
import com.zachery.cms.modules.identity.auth.dto.*;
import com.zachery.cms.modules.identity.auth.service.RegistrationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class RegistrationController {
    private final RegistrationService service;

    public RegistrationController(RegistrationService service) { this.service = service; }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RegisteredUser> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(service.register(request));
    }
}
