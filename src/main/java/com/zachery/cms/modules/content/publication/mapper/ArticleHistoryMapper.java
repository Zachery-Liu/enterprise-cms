package com.zachery.cms.modules.content.publication.mapper;

import org.apache.ibatis.annotations.*;

public interface ArticleHistoryMapper {
    @Select("SELECT * FROM cms_article_status_history WHERE article_id=#{id} ORDER BY created_at,id")
    com.baomidou.mybatisplus.core.metadata.IPage<com.zachery.cms.modules.content.publication.HistoryRow> selectHistoryPage(
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.zachery.cms.modules.content.publication.HistoryRow> page,
            @Param("id") Long id);
    @Insert("INSERT INTO cms_article_status_history(article_id,operator_id,action,from_status,to_status,created_at) "
            + "VALUES (#{articleId},#{operatorId},'CREATE',NULL,'DRAFT',#{createdAt})")
    int insertCreation(@Param("articleId") Long articleId, @Param("operatorId") Long operatorId,
                       @Param("createdAt") java.time.LocalDateTime createdAt);

    @Insert("INSERT INTO cms_article_status_history(article_id,operator_id,action,from_status,to_status,reason,created_at) "
            + "VALUES (#{articleId},#{operatorId},#{action},#{previous},#{target},#{reason},#{createdAt})")
    int insertTransition(@Param("articleId") Long articleId,@Param("operatorId") Long operatorId,
            @Param("action") String action,@Param("previous") String previous,@Param("target") String target,
            @Param("reason") String reason,@Param("createdAt") java.time.LocalDateTime createdAt);
}
