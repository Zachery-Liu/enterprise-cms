package com.zachery.cms.modules.content.article.dto;

import jakarta.validation.constraints.*;

public final class ArticleRequests {
    private ArticleRequests() { }
    public record Create(@NotBlank @Size(max=200) String title, @Size(max=500) String summary,
                         @NotBlank @Size(max=100000) String content, @NotNull @Positive Long categoryId) {
        public Create { title = trim(title); summary = trim(summary); }
    }
    public record Update(@NotBlank @Size(max=200) String title, @Size(max=500) String summary,
                         @NotBlank @Size(max=100000) String content, @NotNull @Positive Long categoryId,
                         @NotNull @PositiveOrZero Integer version) {
        public Update { title = trim(title); summary = trim(summary); }
    }
    private static String trim(String value) { return value == null ? null : value.strip(); }
}
