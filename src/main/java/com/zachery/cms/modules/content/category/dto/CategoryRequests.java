package com.zachery.cms.modules.content.category.dto;

import jakarta.validation.constraints.*;

/** Command-specific inputs; callers cannot supply identity, deletion flags or arbitrary fields. */
public final class CategoryRequests {
    private CategoryRequests() { }

    public record Create(@Positive Long parentId, @NotBlank @Size(max = 64) String name,
                         @PositiveOrZero Integer sortOrder) {
        public Create { name = normalize(name); sortOrder = sortOrder == null ? 0 : sortOrder; }
    }
    public record Rename(@NotBlank @Size(max = 64) String name, @NotNull @PositiveOrZero Integer version) {
        public Rename { name = normalize(name); }
    }
    public record Move(@Positive Long parentId, @NotNull @PositiveOrZero Integer version) { }
    public record Sort(@NotNull @PositiveOrZero Integer sortOrder, @NotNull @PositiveOrZero Integer version) { }
    public record ChangeStatus(@NotNull @Pattern(regexp = "ENABLED|DISABLED") String status,
                               @NotNull @PositiveOrZero Integer version) { }

    private static String normalize(String value) { return value == null ? null : value.strip(); }
}
