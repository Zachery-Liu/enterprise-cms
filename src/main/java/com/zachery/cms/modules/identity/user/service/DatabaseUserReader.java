package com.zachery.cms.modules.identity.user.service;

import com.zachery.cms.common.validation.RequestValidation;
import com.zachery.cms.modules.identity.persistence.mapper.UserSummaryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class DatabaseUserReader implements UserReader {
    public static final int MAX_BATCH_SIZE = 500;
    private final UserSummaryMapper users;
    public DatabaseUserReader(UserSummaryMapper users) { this.users = users; }

    @Override public Map<Long, UserSummary> findByIds(Collection<Long> userIds) {
        List<Long> ids = RequestValidation.ids(userIds, "userIds", MAX_BATCH_SIZE, true);
        if (ids.isEmpty()) return Map.of();
        Map<Long, UserSummary> found = new HashMap<>();
        for (UserSummary user : users.selectSummaries(ids)) found.put(user.id(), user);
        Map<Long, UserSummary> ordered = new LinkedHashMap<>();
        for (Long id : ids) if (found.containsKey(id)) ordered.put(id, found.get(id));
        return Collections.unmodifiableMap(ordered);
    }
}
