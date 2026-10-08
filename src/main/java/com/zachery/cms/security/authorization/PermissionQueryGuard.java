package com.zachery.cms.security.authorization;

import com.zachery.cms.common.exception.*;
import com.zachery.cms.modules.identity.auth.dto.LoginUser;
import com.zachery.cms.modules.identity.role.service.RbacQueryService;
import com.zachery.cms.security.session.SessionKeys;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/** Explicit protection for B-12 read endpoints until the B-14 method aspect is available. */
@Component
public class PermissionQueryGuard {
    private final RbacQueryService queries;

    public PermissionQueryGuard(RbacQueryService queries) { this.queries = queries; }

    public void require(HttpServletRequest request, String permission) {
        Object principal = request.getAttribute(SessionKeys.REQUEST_USER);
        if (!(principal instanceof LoginUser user)) throw new BusinessException(ErrorCode.AUTH_UNAUTHENTICATED);
        if (!queries.effectivePermissionCodes(user.userId()).contains(permission))
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
}
