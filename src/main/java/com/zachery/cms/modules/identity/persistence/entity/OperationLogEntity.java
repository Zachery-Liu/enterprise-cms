package com.zachery.cms.modules.identity.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter
@TableName("sys_operation_log")
public class OperationLogEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private Long operatorId;
    private String operatorName;
    private String module;
    private String action;
    private String targetType;
    private Long targetId;
    private String result;
    private String summary;
    private String errorCode;
    private String requestId;
    private String ipAddress;
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createdAt;
}
