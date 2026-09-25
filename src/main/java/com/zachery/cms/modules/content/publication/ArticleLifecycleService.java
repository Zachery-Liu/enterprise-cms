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

}
