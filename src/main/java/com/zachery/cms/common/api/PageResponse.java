package com.zachery.cms.common.api;

import com.zachery.cms.common.validation.PageQuery;
import java.util.List;

public record PageResponse<T>(List<T> records, long total, long page, long size) {
    public PageResponse {
        records = List.copyOf(records);
        new PageQuery(page, size);
        if (total < 0) throw new IllegalArgumentException("total must not be negative");
    }

    public static <T> PageResponse<T> empty(long page, long size) {
        return new PageResponse<>(List.of(), 0, page, size);
    }
}
