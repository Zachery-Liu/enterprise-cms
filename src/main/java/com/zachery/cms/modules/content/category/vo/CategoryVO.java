package com.zachery.cms.modules.content.category.vo;

import com.zachery.cms.modules.content.category.entity.CategoryEntity;

public record CategoryVO(Long id, Long parentId, String name, Integer sortOrder, String status, Integer version) {
    public static CategoryVO from(CategoryEntity entity) {
        return new CategoryVO(entity.getId(), entity.getParentId(), entity.getName(), entity.getSortOrder(),
                entity.getStatus(), entity.getVersion());
    }
}
