package com.zachery.cms.modules.identity.auth.dto;

import java.time.Instant;

public record RegisteredUser(Long id, String username, String displayName, String status, Instant createdAt) {}
