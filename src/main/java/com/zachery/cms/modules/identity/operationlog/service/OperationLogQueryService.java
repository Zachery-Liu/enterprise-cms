package com.zachery.cms.modules.identity.operationlog.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zachery.cms.common.api.PageResponse;
import com.zachery.cms.modules.identity.operationlog.dto.*;
import com.zachery.cms.modules.identity.persistence.entity.OperationLogEntity;
import com.zachery.cms.modules.identity.persistence.mapper.OperationLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class OperationLogQueryService {
    private final OperationLogMapper logs;
    public OperationLogQueryService(OperationLogMapper logs) { this.logs = logs; }

    // Count and page see the same MySQL snapshot even while new audit rows are inserted.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<OperationLogView> query(OperationLogQuery filter) {
        var conditions = new LambdaQueryWrapper<OperationLogEntity>()
                .eq(filter.operatorId() != null, OperationLogEntity::getOperatorId, filter.operatorId())
                .eq(filter.operatorName() != null, OperationLogEntity::getOperatorName, filter.operatorName())
                .eq(filter.module() != null, OperationLogEntity::getModule, filter.module())
                .eq(filter.action() != null, OperationLogEntity::getAction, filter.action())
                .eq(filter.result() != null, OperationLogEntity::getResult, filter.result())
                .ge(filter.from() != null, OperationLogEntity::getCreatedAt, filter.from())
                .le(filter.to() != null, OperationLogEntity::getCreatedAt, filter.to())
                .orderByDesc(OperationLogEntity::getCreatedAt, OperationLogEntity::getId);
        var page = logs.selectPage(new Page<>(filter.page().page(), filter.page().size()), conditions);
        return new PageResponse<>(page.getRecords().stream().map(OperationLogView::from).toList(),
                page.getTotal(), filter.page().page(), filter.page().size());
    }
}
