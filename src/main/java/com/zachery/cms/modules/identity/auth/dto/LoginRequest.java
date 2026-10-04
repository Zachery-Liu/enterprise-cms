package com.zachery.cms.modules.identity.auth.dto;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.constraints.*;
import java.util.Locale;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LoginRequest(
        @NotBlank(message = "用户名不能为空") @Size(max = 64, message = "用户名不能超过 64 字符")
        String username,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @NotBlank(message = "密码不能为空") @Size(max = 128, message = "密码不能超过 128 字符")
        String password
) {
    public LoginRequest {
        username = username == null ? null : username.strip().toLowerCase(Locale.ROOT);
    }
    @Override public String toString() { return "LoginRequest[redacted]"; }
}
