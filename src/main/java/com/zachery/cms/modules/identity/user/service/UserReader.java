package com.zachery.cms.modules.identity.user.service;

import java.util.Collection;
import java.util.Map;

public interface UserReader {
    Map<Long, UserSummary> findByIds(Collection<Long> userIds);
}
