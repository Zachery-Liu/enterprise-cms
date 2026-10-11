package com.zachery.cms.modules.identity.user.dto;

import jakarta.validation.constraints.*;

public record SetUserStatusRequest(
        @NotNull @Pattern(regexp="ACTIVE|DISABLED", message="状态必须为 ACTIVE 或 DISABLED") String status,
        @NotNull @Min(0) Integer version) {}
