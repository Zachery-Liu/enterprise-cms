package com.zachery.cms.modules.content.article.service;

import com.zachery.cms.common.context.CurrentUserContext;
import com.zachery.cms.common.exception.BusinessException;
import com.zachery.cms.modules.content.article.dto.ArticleRequests.*;
import com.zachery.cms.modules.content.article.entity.ArticleEntity;
import com.zachery.cms.modules.content.article.enums.ArticleStatus;
import com.zachery.cms.modules.content.article.mapper.ArticleMapper;
import com.zachery.cms.modules.content.article.vo.ArticleDetailVO;
import com.zachery.cms.modules.content.category.mapper.CategoryMapper;
import com.zachery.cms.modules.content.category.service.CategoryService;
import com.zachery.cms.modules.content.publication.ArticleLifecycleService;
import com.zachery.cms.modules.identity.user.service.UserReader;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.validation.annotation.Validated;
import java.time.*;
import java.util.*;

@Service @Validated @Transactional(readOnly=true)
public class ArticleService {
    private final ArticleMapper articles;
    private final CategoryService categories;
    private final CategoryMapper categoryRows;
    private final CurrentUserContext users;
    private final UserReader reader;
    private final ArticleContentSanitizer sanitizer;
    private final ArticleLifecycleService lifecycle;
    public ArticleService(ArticleMapper articles, CategoryService categories, CategoryMapper categoryRows,
            CurrentUserContext users, UserReader reader, ArticleContentSanitizer sanitizer, ArticleLifecycleService lifecycle) {
        this.articles=articles; this.categories=categories; this.categoryRows=categoryRows; this.users=users;
        this.reader=reader; this.sanitizer=sanitizer; this.lifecycle=lifecycle;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public ArticleDetailVO create(@NotNull @Valid Create request) {
        return view(lifecycle.createDraft(request));
    }
    public ArticleDetailVO detail(@NotNull @Positive Long id) {
        users.getRequiredUser();
        ArticleEntity row=articles.selectById(id);
        if (row == null) throw missing();
        return view(row);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public ArticleDetailVO update(@NotNull @Positive Long id, @NotNull @Valid Update request) {
        Long actor=users.getRequiredUser().userId();
        categories.requireAvailableForWrite(request.categoryId());
        ArticleEntity row=locked(id,request.version());
        if (!ArticleStatus.valueOf(row.getStatus()).isEditable()) throw conflict();
        row.setTitle(request.title()); row.setSummary(request.summary()); row.setContent(sanitizer.forStorage(request.content()));
        row.setCategoryId(request.categoryId()); row.setUpdatedBy(actor); row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        if (articles.updateContent(row) != 1) throw conflict();
        row.setVersion(row.getVersion()+1);
        return view(row);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void delete(@NotNull @Positive Long id, @NotNull @PositiveOrZero Integer version) {
        Long actor=users.getRequiredUser().userId();
        categories.lockForArticleWrite();
        ArticleEntity row=locked(id,version);
        if (!ArticleStatus.valueOf(row.getStatus()).isDeletable()) throw conflict();
        row.setUpdatedBy(actor); row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        if (articles.markDeleted(row) != 1) throw conflict();
    }
    private ArticleEntity locked(Long id, Integer version) {
        ArticleEntity row=articles.selectForUpdate(id);
        if (row == null) throw missing();
        if (!version.equals(row.getVersion()) || version == Integer.MAX_VALUE) throw conflict();
        return row;
    }
    private ArticleDetailVO view(ArticleEntity row) {
        var ids=new HashSet<Long>(); ids.add(row.getAuthorId());
        if (row.getReviewerId()!=null) ids.add(row.getReviewerId());
        var authors=reader.findByIds(ids);
        var category=categoryRows.selectById(row.getCategoryId());
        var author=authors.get(row.getAuthorId()); var reviewer=row.getReviewerId()==null?null:authors.get(row.getReviewerId());
        return new ArticleDetailVO(row.getId(),row.getTitle(),row.getSummary(),sanitizer.forDisplay(row.getContent()),
                row.getCategoryId(),category==null?"未知分类":category.getName(),row.getAuthorId(),author==null?"未知用户":author.displayName(),
                row.getStatus(),row.getVersion(),utc(row.getCreatedAt()),utc(row.getUpdatedAt()),utc(row.getPublishedAt()),
                row.getReviewerId(),reviewer==null?null:reviewer.displayName(),row.getReviewComment(),utc(row.getReviewedAt()));
    }
    private Instant utc(LocalDateTime time) { return time==null?null:time.toInstant(ZoneOffset.UTC); }
    private BusinessException missing() { return new BusinessException(HttpStatus.NOT_FOUND,"COMMON_NOT_FOUND","文章不存在"); }
    private BusinessException conflict() { return new BusinessException(HttpStatus.CONFLICT,"ARTICLE_CONFLICT","文章状态或版本已变化，请刷新后重试"); }
}
