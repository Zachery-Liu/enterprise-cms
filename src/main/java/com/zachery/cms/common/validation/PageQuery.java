package com.zachery.cms.common.validation;

import com.zachery.cms.common.exception.RequestValidationException;
import java.util.Map;

public record PageQuery(long page, long size) {
    public PageQuery {
        if (page < 1) throw new RequestValidationException("page", "页码必须大于等于 1");
        if (size < 1 || size > 100) throw new RequestValidationException("size", "每页数量必须在 1 到 100 之间");
        if (page - 1 > Long.MAX_VALUE / size)
            throw new RequestValidationException("page", "页码超出支持范围");
    }

    public static PageQuery defaults() { return new PageQuery(1, 20); }

    public static PageQuery parse(Map<String, String> values) {
        return new PageQuery(number(values, "page", 1), number(values, "size", 20));
    }

    public long offset() { return (page - 1) * size; }

    private static long number(Map<String, String> values, String field, long fallback) {
        if (!values.containsKey(field)) return fallback;
        try {
            return Long.parseLong(values.get(field));
        } catch (NumberFormatException e) {
            throw new RequestValidationException(field, "必须为有效整数");
        }
    }
}
