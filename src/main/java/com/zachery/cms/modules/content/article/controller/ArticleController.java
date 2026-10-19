package com.zachery.cms.modules.content.article.controller;

import com.zachery.cms.common.api.*;
import com.zachery.cms.logging.annotation.OperationLog;
import com.zachery.cms.security.annotation.RequirePermission;
import com.zachery.cms.modules.content.article.dto.*;
import com.zachery.cms.modules.content.article.service.*;
import com.zachery.cms.modules.content.article.vo.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @Validated @RequestMapping("/api/articles")
public class ArticleController {
    private final ArticleService articles;
    private final ArticleQueryService queries;
    public ArticleController(ArticleService articles, ArticleQueryService queries) { this.articles=articles; this.queries=queries; }

    @GetMapping @RequirePermission("cms:article:read")
    public ApiResponse<PageResponse<ArticleListVO>> page(@Valid @ModelAttribute ArticlePageQuery query) { return ApiResponse.ok(queries.page(query)); }

    @GetMapping("/{id}") @RequirePermission("cms:article:read")
    public ApiResponse<ArticleDetailVO> get(@PathVariable @Positive Long id) { return ApiResponse.ok(articles.detail(id)); }

    @PostMapping @RequirePermission("cms:article:create")
    @OperationLog(module="ARTICLE", action="ARTICLE_CREATE", targetType="ARTICLE", targetIdExpression="#result.data.id")
    public ApiResponse<ArticleDetailVO> create(@Valid @RequestBody ArticleRequests.Create request) { return ApiResponse.ok(articles.create(request)); }

    @PutMapping("/{id}") @RequirePermission("cms:article:edit")
    @OperationLog(module="ARTICLE", action="ARTICLE_UPDATE", targetType="ARTICLE", targetIdExpression="#id")
    public ApiResponse<ArticleDetailVO> update(@PathVariable @Positive Long id, @Valid @RequestBody ArticleRequests.Update request) { return ApiResponse.ok(articles.update(id,request)); }

    @DeleteMapping("/{id}") @RequirePermission("cms:article:delete")
    @OperationLog(module="ARTICLE", action="ARTICLE_DELETE", targetType="ARTICLE", targetIdExpression="#id")
    public ApiResponse<Void> delete(@PathVariable @Positive Long id, @RequestParam @PositiveOrZero Integer version) { articles.delete(id,version); return ApiResponse.ok(); }
}
