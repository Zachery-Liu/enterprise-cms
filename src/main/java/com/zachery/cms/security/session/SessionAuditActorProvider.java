package com.zachery.cms.security.session;

import com.zachery.cms.modules.identity.auth.dto.LoginUser;
import com.zachery.cms.modules.identity.persistence.AuditActorProvider;
import org.springframework.stereotype.Component;
import java.util.Optional;

/** No database dependency: audit fill must not recursively query its own persistence layer. */
@Component
public class SessionAuditActorProvider implements AuditActorProvider {
    @Override public Optional<Long> currentUserId() {
        return SessionRequestUser.current().map(LoginUser::userId);
    }
}
