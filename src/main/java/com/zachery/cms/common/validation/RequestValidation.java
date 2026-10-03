package com.zachery.cms.common.validation;

import com.zachery.cms.common.exception.RequestValidationException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

public final class RequestValidation {
    private RequestValidation() {}

    /** Validate the original request size before deduplication. Limits are chosen by each endpoint. */
    public static List<Long> ids(Collection<Long> values, String field, int maxSize) {
        return ids(values, field, maxSize, false);
    }

    public static List<Long> ids(Collection<Long> values, String field, int maxSize, boolean allowEmpty) {
        if (maxSize < 1) throw new IllegalArgumentException("maxSize must be positive");
        if (values == null || values.size() > maxSize)
            throw new RequestValidationException(field, "必须提供集合且数量不得超过 " + maxSize);
        if (!allowEmpty && values.isEmpty())
            throw new RequestValidationException(field, "集合不能为空");
        if (values.stream().anyMatch(id -> id == null || id <= 0))
            throw new RequestValidationException(field, "ID 必须为正整数");
        return List.copyOf(new LinkedHashSet<>(values));
    }

    public static long positiveId(String value, String field) {
        try {
            long id = Long.parseLong(value);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {
            // Return the same safe field error for format, overflow and non-positive values.
        }
        throw new RequestValidationException(field, "ID 必须为正整数");
    }

    public record SortField(String column, boolean ascending) {}

    /** Only values from the server-owned map can become SQL column names. */
    public static List<SortField> sort(String value, Map<String, String> allowedColumns) {
        if (value == null) return List.of();
        List<SortField> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String item : value.split(",", -1)) {
            String[] pair = item.split(":", -1);
            if (pair.length != 2 || !allowedColumns.containsKey(pair[0]) || !seen.add(pair[0])
                    || !(pair[1].equals("asc") || pair[1].equals("desc")))
                throw new RequestValidationException("sort", "排序字段或方向不合法");
            result.add(new SortField(allowedColumns.get(pair[0]), pair[1].equals("asc")));
        }
        return List.copyOf(result);
    }

    public static String text(String value, String field, int minLength, int maxLength) {
        if (minLength < 0 || maxLength < minLength) throw new IllegalArgumentException("Invalid length limits");
        if (value == null || value.length() < minLength || value.length() > maxLength)
            throw new RequestValidationException(field, "长度必须在 " + minLength + " 到 " + maxLength + " 之间");
        return value;
    }

    public static Instant time(String value, String field) {
        if (value == null) return null;
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw new RequestValidationException(field, "时间必须包含时区偏移量");
        }
    }

    public static void timeRange(Instant from, Instant to) {
        if (from != null && to != null && from.isAfter(to))
            throw new RequestValidationException("from", "开始时间不能晚于结束时间");
    }
}
