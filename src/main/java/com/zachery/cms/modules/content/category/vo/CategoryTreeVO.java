package com.zachery.cms.modules.content.category.vo;

import java.util.List;

public record CategoryTreeVO(Long id, Long parentId, String name, Integer sortOrder, String status,
                             Integer version, List<CategoryTreeVO> children) {
    public CategoryTreeVO { children = List.copyOf(children); }
}
