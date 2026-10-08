package com.zachery.cms.common.context;

import java.util.Optional;

public interface CurrentUserContext {
    Optional<CurrentUser> getCurrentUser();
    CurrentUser getRequiredUser();
}
