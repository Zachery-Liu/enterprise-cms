package com.zachery.cms.modules.content.publication;

import com.zachery.cms.common.api.*;
import com.zachery.cms.security.annotation.AnonymousAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @Validated @RequestMapping("/api/public/articles")
public class PublicArticleController {
    private final PublicationQueryService queries;
    public PublicArticleController(PublicationQueryService queries) { this.queries=queries; }
    @GetMapping @AnonymousAccess
    public ApiResponse<PageResponse<PublicArticleVO>> page(@Valid @ModelAttribute PublicationPageQuery query) { return ApiResponse.ok(queries.publicPage(query)); }
    @GetMapping("/{id}") @AnonymousAccess
    public ApiResponse<PublicArticleDetailVO> detail(@PathVariable @Positive Long id) { return ApiResponse.ok(queries.publicDetail(id)); }
}
