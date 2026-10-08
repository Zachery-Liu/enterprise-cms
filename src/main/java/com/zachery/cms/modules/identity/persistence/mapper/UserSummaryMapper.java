package com.zachery.cms.modules.identity.persistence.mapper;

import com.zachery.cms.modules.identity.user.service.UserSummary;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface UserSummaryMapper {
    List<UserSummary> selectSummaries(@Param("userIds") List<Long> userIds);
}
