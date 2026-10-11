package com.zachery.cms.modules.identity.user.dto;

import com.zachery.cms.common.exception.RequestValidationException;
import com.zachery.cms.common.validation.*;
import java.time.*;
import java.util.Map;

public record UserQuery(PageQuery page, String username, String status, LocalDateTime from, LocalDateTime to) {
    public static UserQuery parse(Map<String,String> values) {
        PageQuery page = PageQuery.parse(values);
        String name = values.get("username"), status = values.get("status");
        if (name != null && (name.isBlank() || name.length() > 64))
            throw new RequestValidationException("username", "用户名关键词长度必须为 1 到 64，且不能全为空白");
        if (status != null && !status.equals("ACTIVE") && !status.equals("DISABLED"))
            throw new RequestValidationException("status", "状态必须为 ACTIVE 或 DISABLED");
        Instant from = time(values.get("from"), "from"), to = time(values.get("to"), "to");
        RequestValidation.timeRange(from, to);
        return new UserQuery(page, name, status, utc(from), utc(to));
    }
    public String usernamePattern() {
        return username == null ? null : "%" + username.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
    private static Instant time(String value, String field) {
        Instant instant = RequestValidation.time(value, field);
        if (instant != null && (instant.isBefore(Instant.parse("1000-01-01T00:00:00Z"))
                || instant.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")) || instant.getNano() % 1_000_000 != 0))
            throw new RequestValidationException(field, "UTC 时间须在 1000 至 9999 年之间，精度最多为毫秒");
        return instant;
    }
    private static LocalDateTime utc(Instant value) { return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
}
