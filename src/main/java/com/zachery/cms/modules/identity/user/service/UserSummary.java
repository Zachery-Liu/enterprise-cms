package com.zachery.cms.modules.identity.user.service;

import com.zachery.cms.common.context.UserStatus;

public record UserSummary(Long id, String username, String displayName, UserStatus status) {}
