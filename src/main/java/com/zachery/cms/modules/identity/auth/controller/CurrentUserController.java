package com.zachery.cms.modules.identity.auth.controller;

import com.zachery.cms.common.api.ApiResponse;
import com.zachery.cms.common.context.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class CurrentUserController {
    private final CurrentUserContext users;
    public CurrentUserController(CurrentUserContext users) { this.users = users; }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<CurrentUser>> me() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(users.getRequiredUser()));
    }
}
