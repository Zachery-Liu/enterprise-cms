package com.zachery.cms.modules.content.publication;

import com.zachery.cms.common.api.*;
import com.zachery.cms.logging.annotation.OperationLog;
import com.zachery.cms.security.annotation.RequirePermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @Validated @RequestMapping("/api/articles")
public class ArticleLifecycleController {
    private final ArticleLifecycleService lifecycle;
    private final PublicationBatchService batches;
    private final PublicationQueryService queries;
    public ArticleLifecycleController(ArticleLifecycleService lifecycle, PublicationBatchService batches, PublicationQueryService queries) {
        this.lifecycle=lifecycle; this.batches=batches; this.queries=queries;
    }

    @PostMapping("/{id}/submit") @RequirePermission("cms:article:submit")
    @OperationLog(module="ARTICLE", action="ARTICLE_SUBMIT", targetType="ARTICLE", targetIdExpression="#id")
    public ApiResponse<TransitionResult> submit(@PathVariable @Positive Long id, @Valid @RequestBody TransitionCommand request) { return act(id,ArticleAction.SUBMIT,request); }
    @PostMapping("/{id}/approve") @RequirePermission("cms:article:audit")
    @OperationLog(module="ARTICLE", action="ARTICLE_APPROVE", targetType="ARTICLE", targetIdExpression="#id")
    public ApiResponse<TransitionResult> approve(@PathVariable @Positive Long id, @Valid @RequestBody TransitionCommand request) { return act(id,ArticleAction.APPROVE,request); }
    @PostMapping("/{id}/reject") @RequirePermission("cms:article:audit")
    @OperationLog(module="ARTICLE", action="ARTICLE_REJECT", targetType="ARTICLE", targetIdExpression="#id")
    public ApiResponse<TransitionResult> reject(@PathVariable @Positive Long id, @Valid @RequestBody TransitionCommand request) { return act(id,ArticleAction.REJECT,request); }
    @PostMapping("/{id}/publish") @RequirePermission("cms:article:publish")
    @OperationLog(module="ARTICLE", action="ARTICLE_PUBLISH", targetType="ARTICLE", targetIdExpression="#id")
    public ApiResponse<TransitionResult> publish(@PathVariable @Positive Long id, @Valid @RequestBody TransitionCommand request) { return act(id,ArticleAction.PUBLISH,request); }
    @PostMapping("/{id}/offline") @RequirePermission("cms:article:offline")
    @OperationLog(module="ARTICLE", action="ARTICLE_OFFLINE", targetType="ARTICLE", targetIdExpression="#id")
    public ApiResponse<TransitionResult> offline(@PathVariable @Positive Long id, @Valid @RequestBody TransitionCommand request) { return act(id,ArticleAction.OFFLINE,request); }
    @PostMapping("/{id}/reopen") @RequirePermission("cms:article:edit")
    @OperationLog(module="ARTICLE", action="ARTICLE_REOPEN", targetType="ARTICLE", targetIdExpression="#id")
    public ApiResponse<TransitionResult> reopen(@PathVariable @Positive Long id, @Valid @RequestBody TransitionCommand request) { return act(id,ArticleAction.REOPEN,request); }

    @PostMapping("/batch-publish") @RequirePermission("cms:article:publish")
    @OperationLog(module="ARTICLE", action="ARTICLE_BATCH_PUBLISH", targetType="ARTICLE")
    public ApiResponse<BatchPublishResult> publishBatch(@Valid @RequestBody BatchPublishCommand request) { return ApiResponse.ok(batches.publish(request)); }

    @GetMapping("/review-queue") @RequirePermission("cms:article:audit")
    public ApiResponse<PageResponse<ReviewArticleVO>> reviewQueue(@Valid @ModelAttribute ReviewQueueQuery query) { return ApiResponse.ok(queries.reviewQueue(query)); }
    @GetMapping("/{id}/history") @RequirePermission("cms:article:read")
    public ApiResponse<PageResponse<HistoryVO>> history(@PathVariable @Positive Long id,
            @RequestParam(defaultValue="1") @Min(1) int page, @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) { return ApiResponse.ok(queries.history(id,page,size)); }

    private ApiResponse<TransitionResult> act(Long id, ArticleAction action, TransitionCommand request) { return ApiResponse.ok(lifecycle.transition(id,action,request)); }
}
