package com.zachery.cms.modules.content.article.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zachery.cms.modules.content.article.entity.ArticleEntity;
import org.apache.ibatis.annotations.Param;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zachery.cms.modules.content.article.dto.ArticlePageRow;
import com.zachery.cms.modules.content.article.dto.ArticlePageQuery;

public interface ArticleMapper extends BaseMapper<ArticleEntity> {
    IPage<ArticlePageRow> selectArticlePage(Page<ArticlePageRow> page, @Param("query") ArticlePageQuery query);
    ArticleEntity selectForUpdate(@Param("id") Long id);
    java.util.List<ArticleEntity> selectBatchForUpdate(@Param("ids") java.util.Collection<Long> ids);
    int updateContent(@Param("row") ArticleEntity row);
    int markDeleted(@Param("row") ArticleEntity row);
    int updateLifecycle(@Param("row") ArticleEntity row, @Param("previous") String previous);
    IPage<ArticlePageRow> selectPublicPage(Page<ArticlePageRow> page,
            @Param("query") com.zachery.cms.modules.content.publication.PublicationPageQuery query);
    ArticleEntity selectPublished(@Param("id") Long id);
    IPage<ArticlePageRow> selectReviewPage(Page<ArticlePageRow> page,
            @Param("query") com.zachery.cms.modules.content.publication.ReviewQueueQuery query);
}
