package com.zachery.cms.security.session;

import com.zachery.cms.common.context.*;
import com.zachery.cms.common.exception.*;
import com.zachery.cms.modules.identity.role.dto.RoleSummary;
import com.zachery.cms.modules.identity.role.service.RbacQueryService;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class SessionCurrentUserContext implements CurrentUserContext {
    private final RbacQueryService queries;

    public SessionCurrentUserContext(RbacQueryService queries) { this.queries = queries; }

    @Override public Optional<CurrentUser> getCurrentUser() {
        return SessionRequestUser.current().map(user -> {
            var request = SessionRequestUser.request();
            Object existing = request.getAttribute(SessionKeys.CURRENT_USER_SNAPSHOT);
            if (existing instanceof CurrentUser snapshot && snapshot.userId().equals(user.userId())) return snapshot;
            Set<String> roles = queries.rolesForUsers(List.of(user.userId())).get(user.userId()).stream()
                    .map(RoleSummary::code).collect(Collectors.toSet());
            CurrentUser current = new CurrentUser(user.userId(), user.username(), user.displayName(),
                    UserStatus.valueOf(user.status()), roles, new HashSet<>(queries.effectivePermissionCodes(user.userId())));
            request.setAttribute(SessionKeys.CURRENT_USER_SNAPSHOT, current);
            return current;
        });
    }

    @Override public CurrentUser getRequiredUser() {
        return getCurrentUser().orElseThrow(() -> new BusinessException(ErrorCode.AUTH_UNAUTHENTICATED));
    }
}
