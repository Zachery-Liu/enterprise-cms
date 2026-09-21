package com.zachery.cms.modules.identity.persistence;

import java.util.Optional;

/** Supplies the authenticated actor for audit auto-fill; B-10 will provide the session-backed implementation. */
@FunctionalInterface
public interface AuditActorProvider {
    Optional<Long> currentUserId();
}
