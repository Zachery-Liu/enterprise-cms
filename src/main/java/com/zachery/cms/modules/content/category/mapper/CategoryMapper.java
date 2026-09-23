package com.zachery.cms.modules.content.category.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zachery.cms.modules.content.category.entity.CategoryEntity;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface CategoryMapper extends BaseMapper<CategoryEntity> {
    List<CategoryEntity> selectVisibleTreeRows();
    List<Long> lockAllRows();
    List<CategoryEntity> selectAllForUpdate();
    List<Long> findSiblingForUpdate(@Param("parentId") Long parentId, @Param("name") String name,
                                   @Param("excludeId") Long excludeId);
    List<Long> findArticleReferencesForUpdate(@Param("categoryId") Long categoryId);
    int updateCategory(@Param("row") CategoryEntity row);
}
