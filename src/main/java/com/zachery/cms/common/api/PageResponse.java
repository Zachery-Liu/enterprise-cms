package com.zachery.cms.common.api;

import java.util.List;

/** Database-backed page result; pages are one-based. */
public record PageResponse<T>(List<T> records, long total, long page, long size) {
    public PageResponse {
        records = records == null ? List.of() : List.copyOf(records);
    }

    public static <T> PageResponse<T> of(List<T> records, long total, long page, long size) {
        return new PageResponse<>(records, total, page, size);
    }
}
