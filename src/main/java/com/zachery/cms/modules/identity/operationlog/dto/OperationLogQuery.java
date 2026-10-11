package com.zachery.cms.modules.identity.operationlog.dto;

import com.zachery.cms.common.exception.RequestValidationException;
import com.zachery.cms.common.validation.*;
import java.time.*;
import java.util.Map;

/** Exact filters; UTC millisecond bounds are inclusive, matching DATETIME(3). */
public record OperationLogQuery(PageQuery page, Long operatorId, String operatorName,
        String module, String action, String result, LocalDateTime from, LocalDateTime to) {
    public static OperationLogQuery parse(Map<String, String> values) {
        PageQuery page = PageQuery.parse(values);
        Long operator = values.containsKey("operatorId")
                ? RequestValidation.positiveId(values.get("operatorId"), "operatorId") : null;
        String name = values.get("operatorName");
        if (name != null && (name.isBlank() || name.length() > 100))
            throw new RequestValidationException("operatorName", "操作者名称长度必须为 1 到 100，且不能为空白");
        String module = code(values.get("module"), "module"), action = code(values.get("action"), "action");
        String result = values.get("result");
        if (result != null && !result.equals("SUCCESS") && !result.equals("FAILURE"))
            throw new RequestValidationException("result", "结果必须为 SUCCESS 或 FAILURE");
        Instant from = time(values.get("from"), "from"), to = time(values.get("to"), "to");
        RequestValidation.timeRange(from, to);
        return new OperationLogQuery(page, operator, name, module, action, result,
                utc(from), utc(to));
    }
    private static String code(String value, String field) {
        if (value != null && !value.matches("[A-Z][A-Z0-9_]{0,63}"))
            throw new RequestValidationException(field, "必须为最多 64 位的大写字母、数字或下划线编码，且以字母开头");
        return value;
    }
    private static Instant time(String value, String field) {
        Instant instant = RequestValidation.time(value, field);
        if (instant != null && (instant.isBefore(Instant.parse("1000-01-01T00:00:00Z"))
                || instant.isAfter(Instant.parse("9999-12-31T23:59:59.999Z"))
                || instant.getNano() % 1_000_000 != 0))
            throw new RequestValidationException(field, "UTC 时间须在 1000 至 9999 年之间，精度最多为毫秒");
        return instant;
    }
    private static LocalDateTime utc(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
