package com.zachery.cms.modules.content.publication;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zachery.cms.common.api.PageResponse;
import com.zachery.cms.common.context.CurrentUserContext;
import com.zachery.cms.common.exception.BusinessException;
import com.zachery.cms.modules.content.article.dto.ArticlePageRow;
import com.zachery.cms.modules.content.article.mapper.ArticleMapper;
import com.zachery.cms.modules.content.article.service.ArticleContentSanitizer;
import com.zachery.cms.modules.content.category.mapper.CategoryMapper;
import com.zachery.cms.modules.content.publication.mapper.ArticleHistoryMapper;
import com.zachery.cms.modules.identity.user.service.UserReader;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.validation.annotation.Validated;
import java.time.*;
import java.util.*;

@Service @Validated @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class PublicationQueryService {
    private final ArticleMapper articles;
    private final ArticleHistoryMapper history;
    private final CategoryMapper categories;
    private final UserReader users;
    private final CurrentUserContext identity;
    private final ArticleContentSanitizer sanitizer;
    public PublicationQueryService(ArticleMapper articles,ArticleHistoryMapper history,CategoryMapper categories,
            UserReader users,CurrentUserContext identity,ArticleContentSanitizer sanitizer) {
        this.articles=articles;this.history=history;this.categories=categories;this.users=users;this.identity=identity;this.sanitizer=sanitizer;
    }
    public PageResponse<PublicArticleVO> publicPage(@NotNull @Valid PublicationPageQuery query) {
        var request=new Page<ArticlePageRow>(query.page(),query.size());request.setOptimizeJoinOfCountSql(false);
        var page=articles.selectPublicPage(request,query);
        Set<Long> ids=new HashSet<>();page.getRecords().forEach(row->ids.add(row.getAuthorId()));
        var authors=ids.isEmpty()?Map.<Long,com.zachery.cms.modules.identity.user.service.UserSummary>of():users.findByIds(ids);
        var records=page.getRecords().stream().map(row->new PublicArticleVO(row.getId(),row.getTitle(),row.getSummary(),
                row.getCategoryId(),row.getCategoryName(),authors.containsKey(row.getAuthorId())?authors.get(row.getAuthorId()).displayName():"未知用户",
                utc(row.getPublishedAt()))).toList();
        return new PageResponse<>(records,page.getTotal(),query.page(),query.size());
    }
    public PublicArticleDetailVO publicDetail(@NotNull @Positive Long id) {
        var row=articles.selectPublished(id);if(row==null) throw missing();
        var category=categories.selectById(row.getCategoryId());var author=users.findByIds(List.of(row.getAuthorId())).get(row.getAuthorId());
        return new PublicArticleDetailVO(row.getId(),row.getTitle(),row.getSummary(),row.getCategoryId(),category==null?"未知分类":category.getName(),
                author==null?"未知用户":author.displayName(),utc(row.getPublishedAt()),sanitizer.forDisplay(row.getContent()));
    }
    public PageResponse<HistoryVO> history(@NotNull @Positive Long id,@Min(1) int page,@Min(1) @Max(100) int size) {
        identity.getRequiredUser();if(articles.selectById(id)==null) throw missing();
        var result=history.selectHistoryPage(new Page<HistoryRow>(page,size),id);
        Set<Long> ids=new HashSet<>();result.getRecords().forEach(row->ids.add(row.getOperatorId()));
        var operators=ids.isEmpty()?Map.<Long,com.zachery.cms.modules.identity.user.service.UserSummary>of():users.findByIds(ids);
        var records=result.getRecords().stream().map(row->new HistoryVO(row.getId(),row.getArticleId(),row.getAction(),row.getFromStatus(),
                row.getToStatus(),row.getReason(),row.getOperatorId(),operators.containsKey(row.getOperatorId())?operators.get(row.getOperatorId()).displayName():"未知用户",
                utc(row.getCreatedAt()))).toList();
        return new PageResponse<>(records,result.getTotal(),page,size);
    }
    public PageResponse<ReviewArticleVO> reviewQueue(@NotNull @Valid ReviewQueueQuery query) {
        identity.getRequiredUser();
        if(query.submittedFrom()!=null&&query.submittedTo()!=null&&!query.submittedFrom().toInstant().isBefore(query.submittedTo().toInstant()))
            throw new BusinessException(HttpStatus.BAD_REQUEST,"COMMON_VALIDATION_ERROR","提交时间起点必须早于终点");
        var request=new Page<ArticlePageRow>(query.page(),query.size());request.setOptimizeJoinOfCountSql(false);
        var result=articles.selectReviewPage(request,query);
        Set<Long> ids=new HashSet<>();result.getRecords().forEach(row->ids.add(row.getAuthorId()));
        var authors=ids.isEmpty()?Map.<Long,com.zachery.cms.modules.identity.user.service.UserSummary>of():users.findByIds(ids);
        var records=result.getRecords().stream().map(row->new ReviewArticleVO(row.getId(),row.getTitle(),row.getSummary(),row.getCategoryId(),
                row.getCategoryName(),row.getAuthorId(),authors.containsKey(row.getAuthorId())?authors.get(row.getAuthorId()).displayName():"未知用户",
                row.getStatus(),row.getVersion(),utc(row.getCreatedAt()),utc(row.getUpdatedAt()),utc(row.getPublishedAt()),utc(row.getSubmittedAt()))).toList();
        return new PageResponse<>(records,result.getTotal(),query.page(),query.size());
    }
    private Instant utc(LocalDateTime time) {return time==null?null:time.toInstant(ZoneOffset.UTC);}
    private BusinessException missing() {return new BusinessException(HttpStatus.NOT_FOUND,"COMMON_NOT_FOUND","文章不存在");}
}
