package com.zachery.cms.common.context;

import java.util.*;

public record CurrentUser(Long userId, String username, String displayName, UserStatus status,
                          Set<String> roleCodes, Set<String> permissionCodes) {
    public CurrentUser {
        roleCodes = Collections.unmodifiableSet(new TreeSet<>(roleCodes));
        permissionCodes = Collections.unmodifiableSet(new TreeSet<>(permissionCodes));
    }
}
