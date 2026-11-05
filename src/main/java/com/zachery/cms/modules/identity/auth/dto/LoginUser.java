package com.zachery.cms.modules.identity.auth.dto;

/** Minimal login result; roles, permissions and /auth/me belong to B-10. */
public record LoginUser(Long userId, String username, String displayName, String status) {}
