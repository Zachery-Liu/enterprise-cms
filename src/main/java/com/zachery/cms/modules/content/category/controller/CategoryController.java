package com.zachery.cms.modules.content.category.controller;

import com.zachery.cms.common.api.ApiResponse;
import com.zachery.cms.logging.annotation.OperationLog;
import com.zachery.cms.security.annotation.RequirePermission;
import com.zachery.cms.modules.content.category.dto.CategoryRequests.*;
import com.zachery.cms.modules.content.category.service.CategoryService;
import com.zachery.cms.modules.content.category.vo.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @Validated @RequestMapping("/api/categories")
public class CategoryController {
    private final CategoryService categories;
    public CategoryController(CategoryService categories) { this.categories = categories; }

    @GetMapping("/tree") @RequirePermission("cms:category:read")
    public ApiResponse<List<CategoryTreeVO>> tree(@RequestParam(required=false) @Positive Long rootId) {
        return ApiResponse.ok(categories.getTree(rootId));
    }
    @GetMapping("/{id}") @RequirePermission("cms:category:read")
    public ApiResponse<CategoryVO> get(@PathVariable @Positive Long id) { return ApiResponse.ok(categories.get(id)); }

    @PostMapping @RequirePermission("cms:category:manage")
    @OperationLog(module="CATEGORY", action="CATEGORY_CREATE", targetType="CATEGORY", targetIdExpression="#result.data.id")
    public ApiResponse<CategoryVO> create(@Valid @RequestBody Create request) { return ApiResponse.ok(categories.create(request)); }

    @PutMapping("/{id}") @RequirePermission("cms:category:manage")
    @OperationLog(module="CATEGORY", action="CATEGORY_UPDATE", targetType="CATEGORY", targetIdExpression="#id")
    public ApiResponse<CategoryVO> rename(@PathVariable @Positive Long id, @Valid @RequestBody Rename request) { return ApiResponse.ok(categories.rename(id,request)); }

    @PatchMapping("/{id}/parent") @RequirePermission("cms:category:manage")
    @OperationLog(module="CATEGORY", action="CATEGORY_MOVE", targetType="CATEGORY", targetIdExpression="#id")
    public ApiResponse<CategoryVO> move(@PathVariable @Positive Long id, @Valid @RequestBody Move request) { return ApiResponse.ok(categories.move(id,request)); }

    @PatchMapping("/{id}/sort") @RequirePermission("cms:category:manage")
    @OperationLog(module="CATEGORY", action="CATEGORY_SORT", targetType="CATEGORY", targetIdExpression="#id")
    public ApiResponse<CategoryVO> sort(@PathVariable @Positive Long id, @Valid @RequestBody Sort request) { return ApiResponse.ok(categories.changeSort(id,request)); }

    @PatchMapping("/{id}/status") @RequirePermission("cms:category:manage")
    @OperationLog(module="CATEGORY", action="CATEGORY_STATUS", targetType="CATEGORY", targetIdExpression="#id")
    public ApiResponse<CategoryVO> status(@PathVariable @Positive Long id, @Valid @RequestBody ChangeStatus request) { return ApiResponse.ok(categories.changeStatus(id,request)); }

    @DeleteMapping("/{id}") @RequirePermission("cms:category:manage")
    @OperationLog(module="CATEGORY", action="CATEGORY_DELETE", targetType="CATEGORY", targetIdExpression="#id")
    public ApiResponse<Void> delete(@PathVariable @Positive Long id, @RequestParam @PositiveOrZero Integer version) {
        categories.delete(id,version); return ApiResponse.ok();
    }
}
