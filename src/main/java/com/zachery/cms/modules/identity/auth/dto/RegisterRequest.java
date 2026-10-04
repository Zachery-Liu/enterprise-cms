package com.zachery.cms.modules.identity.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import java.util.Locale;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RegisterRequest(
        @NotBlank(message = "用户名不能为空")
        @Pattern(regexp = "[a-z0-9_.-]{3,64}", message = "用户名须为 3 到 64 位字母、数字、下划线、点或连字符")
        String username,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 128, message = "密码长度须为 8 到 128")
        String password,
        @NotBlank(message = "展示名称不能为空")
        @Size(max = 100, message = "展示名称不能超过 100 字符")
        String displayName
) {
    public RegisterRequest {
        username = username == null ? null : username.strip().toLowerCase(Locale.ROOT);
        displayName = displayName == null ? username : displayName.strip();
    }

    @Override public String toString() { return "RegisterRequest[redacted]"; }
}
