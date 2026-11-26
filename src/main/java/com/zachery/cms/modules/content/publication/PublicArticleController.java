package com.zachery.cms.modules.content.publication;

import java.util.List;
import com.zachery.cms.modules.content.category.vo.PublicCategory;
import com.zachery.cms.common.api.*;
import com.zachery.cms.security.annotation.AnonymousAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @Validated @RequestMapping("/api/public")
public class PublicArticleController {
    private final PublicationQueryService queries;
    public PublicArticleController(PublicationQueryService queries) { this.queries=queries; }
    @GetMapping("/categories") @AnonymousAccess
    public ApiResponse<List<PublicCategory>> categories() {
        return ApiResponse.ok(queries.publicCategories());
    }
    @GetMapping("/articles") @AnonymousAccess
    public ApiResponse<PageResponse<PublicArticleVO>> page(@Valid @ModelAttribute PublicationPageQuery query) { return ApiResponse.ok(queries.publicPage(query)); }
    @GetMapping("/articles/{id}") @AnonymousAccess
    public ApiResponse<PublicArticleDetailVO> detail(@PathVariable @Positive Long id) { return ApiResponse.ok(queries.publicDetail(id)); }
}
