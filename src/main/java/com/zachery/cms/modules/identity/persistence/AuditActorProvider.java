package com.zachery.cms.modules.identity.persistence;

import java.util.Optional;

/** Supplies the authenticated actor for audit auto-fill; anonymous/background work has no actor. */
@FunctionalInterface
public interface AuditActorProvider {
    Optional<Long> currentUserId();
}
