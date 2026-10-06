package com.zachery.cms.modules.content.publication;

import com.zachery.cms.common.context.CurrentUserContext;
import com.zachery.cms.modules.content.article.entity.ArticleEntity;
import com.zachery.cms.modules.content.article.mapper.ArticleMapper;
import com.zachery.cms.modules.content.article.dto.ArticleRequests.Create;
import com.zachery.cms.modules.content.article.service.ArticleContentSanitizer;
import com.zachery.cms.modules.content.category.service.CategoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import com.zachery.cms.modules.content.publication.mapper.ArticleHistoryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import com.zachery.cms.common.exception.BusinessException;
import com.zachery.cms.modules.content.article.enums.ArticleStatus;
import org.springframework.http.HttpStatus;
import jakarta.validation.constraints.Positive;
import java.util.*;

/** Sole application entry for creation and all state writes; HTTP authorization awaits B integration. */
@Service @Validated
public class ArticleLifecycleService {
    private final ArticleMapper articles;
    private final ArticleHistoryMapper history;
    private final CurrentUserContext users;
    private final CategoryService categories;
    private final ArticleContentSanitizer sanitizer;
    public ArticleLifecycleService(ArticleMapper articles, ArticleHistoryMapper history, CurrentUserContext users,
            CategoryService categories, ArticleContentSanitizer sanitizer) {
        this.articles=articles; this.history=history; this.users=users;
        this.categories=categories; this.sanitizer=sanitizer;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public ArticleEntity createDraft(@NotNull @Valid Create request) {
        Long actor = users.getRequiredUser().userId();
        categories.requireAvailableForWrite(request.categoryId());
        var article = new ArticleEntity();
        article.setTitle(request.title()); article.setSummary(request.summary()); article.setCategoryId(request.categoryId());
        article.setContent(sanitizer.forStorage(request.content()));
        article.setStatus("DRAFT"); article.setVersion(0); article.setDeleted(0);
        article.setAuthorId(actor); article.setCreatedBy(actor); article.setUpdatedBy(actor);
        article.setReviewComment(null); article.setReviewerId(null); article.setReviewedAt(null); article.setPublishedAt(null);
        var now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        article.setCreatedAt(now); article.setUpdatedAt(now);
        if (articles.insert(article) != 1 || history.insertCreation(article.getId(),actor,now) != 1)
            throw new IllegalStateException("Article creation or history insert failed");
        return article;
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public TransitionResult transition(@NotNull @Positive Long id,@NotNull ArticleAction action,
            @NotNull @Valid TransitionCommand command) {
        Long actor=users.getRequiredUser().userId();
        categories.lockForArticleWrite();
        var row=articles.selectForUpdate(id);
        if(row==null) throw new BusinessException(HttpStatus.NOT_FOUND,"COMMON_NOT_FOUND","文章不存在");
        if(!row.getVersion().equals(command.version()) || command.version()==Integer.MAX_VALUE)
            throw new BusinessException(HttpStatus.CONFLICT,"ARTICLE_VERSION_CONFLICT","文章版本已变化，请刷新后重试");
        String previous=row.getStatus();
        ArticleStatus target=ArticleTransitionPolicy.transition(ArticleStatus.valueOf(previous),action,command.comment());
        if(action==ArticleAction.SUBMIT || action==ArticleAction.PUBLISH) {
            categories.requireAvailableForWrite(row.getCategoryId());
            if(row.getTitle()==null || row.getTitle().isBlank() || row.getTitle().length()>200)
                throw new BusinessException(HttpStatus.BAD_REQUEST,"COMMON_VALIDATION_ERROR","文章标题无效");
            sanitizer.forStorage(row.getContent());
        }
        var now=LocalDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        return applyTransition(row,previous,target,action,command.comment(),actor,now);
    }

    private TransitionResult applyTransition(ArticleEntity row,String previous,ArticleStatus target,ArticleAction action,
            String suppliedComment,Long actor,LocalDateTime now) {
        String comment=suppliedComment==null?null:suppliedComment.strip();
        if(action==ArticleAction.APPROVE || action==ArticleAction.REJECT) {
            row.setReviewerId(actor);row.setReviewedAt(now);row.setReviewComment(comment);
        }
        if(action==ArticleAction.SUBMIT || action==ArticleAction.REOPEN) {
            row.setReviewerId(null);row.setReviewedAt(null);row.setReviewComment(null);
        }
        if(action==ArticleAction.PUBLISH) row.setPublishedAt(now);
        if(action==ArticleAction.REOPEN) row.setPublishedAt(null);
        row.setStatus(target.name());row.setUpdatedBy(actor);row.setUpdatedAt(now);
        if(articles.updateLifecycle(row,previous)!=1)
            throw new BusinessException(HttpStatus.CONFLICT,"ARTICLE_VERSION_CONFLICT","文章状态或版本已变化");
        if(history.insertTransition(row.getId(),actor,action.name(),previous,target.name(),comment,now)!=1)
            throw new IllegalStateException("State history insert failed");
        return new TransitionResult(row.getId(),target.name(),row.getVersion()+1,now.toInstant(ZoneOffset.UTC));
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public BatchPublishResult publishBatch(@NotNull @Valid BatchPublishCommand command) {
        Long actor=users.getRequiredUser().userId();
        Set<Long> ids=new TreeSet<>();
        for(var item:command.items()) if(!ids.add(item.id())) throw new BatchPublicationException(HttpStatus.BAD_REQUEST,
                List.of(new BatchPublicationException.Failure(item.id(),"COMMON_VALIDATION_ERROR","文章 ID 重复")));
        categories.lockForArticleWrite();
        var rows=articles.selectBatchForUpdate(ids);
        Map<Long,ArticleEntity> indexed=new HashMap<>();rows.forEach(row->indexed.put(row.getId(),row));
        var badCategories=categories.unavailableForWrites(rows.stream().map(ArticleEntity::getCategoryId).toList());
        var failures=new ArrayList<BatchPublicationException.Failure>();
        for(var item:command.items()) {
            var row=indexed.get(item.id());
            if(row==null){failures.add(new BatchPublicationException.Failure(item.id(),"COMMON_NOT_FOUND","文章不存在或已删除"));continue;}
            if(!row.getVersion().equals(item.version())||item.version()==Integer.MAX_VALUE) {
                failures.add(new BatchPublicationException.Failure(item.id(),"ARTICLE_VERSION_CONFLICT","文章版本已变化"));continue;
            }
            try {
                ArticleTransitionPolicy.transition(ArticleStatus.valueOf(row.getStatus()),ArticleAction.PUBLISH,null);
                if(badCategories.containsKey(row.getCategoryId())) throw new BusinessException(HttpStatus.CONFLICT,"CATEGORY_UNAVAILABLE",badCategories.get(row.getCategoryId()));
                if(row.getTitle()==null||row.getTitle().isBlank()||row.getTitle().length()>200)
                    throw new BusinessException(HttpStatus.BAD_REQUEST,"COMMON_VALIDATION_ERROR","文章标题无效");
                sanitizer.forStorage(row.getContent());
            } catch(BusinessException error){failures.add(new BatchPublicationException.Failure(item.id(),error.getCode(),error.getMessage()));}
        }
        if(!failures.isEmpty()) throw new BatchPublicationException(HttpStatus.CONFLICT,failures);
        var now=LocalDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        Map<Long,TransitionResult> results=new HashMap<>();
        for(var row:rows) {
            try {results.put(row.getId(),applyTransition(row,"APPROVED",ArticleStatus.PUBLISHED,ArticleAction.PUBLISH,null,actor,now));}
            catch(BusinessException error) {
                throw new BatchPublicationException(HttpStatus.CONFLICT,List.of(new BatchPublicationException.Failure(row.getId(),error.getCode(),error.getMessage())));
            }
            catch(org.springframework.dao.DataAccessException error) {
                var failure=new BatchPublicationException(HttpStatus.CONFLICT,List.of(new BatchPublicationException.Failure(row.getId(),"ARTICLE_BATCH_WRITE_FAILED","文章或历史写入失败，请刷新后重试")));
                failure.initCause(error);throw failure;
            }
        }
        return new BatchPublishResult(rows.size(),command.items().stream().map(item->results.get(item.id())).toList());
    }
}
