package com.zachery.cms.modules.content.category.service;

import com.zachery.cms.common.exception.BusinessException;
import com.zachery.cms.modules.content.category.entity.CategoryEntity;
import com.zachery.cms.modules.content.category.vo.CategoryTreeVO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.util.*;

/** Iterative assembly: one input snapshot, no database access or recursive stack depth limit. */
@Component
public class CategoryTreeAssembler {
    public List<CategoryTreeVO> assemble(List<CategoryEntity> rows, Long rootId) {
        Map<Long, CategoryEntity> nodes = new HashMap<>();
        Map<Long, List<CategoryEntity>> children = new HashMap<>();
        for (CategoryEntity row : rows) {
            if (nodes.put(row.getId(), row) != null) throw invalidTree();
            children.computeIfAbsent(row.getParentId(), ignored -> new ArrayList<>()).add(row);
        }
        for (CategoryEntity row : rows) {
            if (row.getParentId() != null && !nodes.containsKey(row.getParentId())) throw invalidTree();
        }
        var order = Comparator.comparing(CategoryEntity::getSortOrder).thenComparing(CategoryEntity::getId);
        children.values().forEach(siblings -> siblings.sort(order));
        var traversal = new ArrayList<CategoryEntity>();
        Deque<CategoryEntity> queue = new ArrayDeque<>(children.getOrDefault(null, List.of()));
        while (!queue.isEmpty()) {
            CategoryEntity node = queue.removeFirst();
            traversal.add(node);
            queue.addAll(children.getOrDefault(node.getId(), List.of()));
        }
        // A parent pointer has one outgoing edge; unreachable nodes imply a disconnected cycle.
        if (traversal.size() != nodes.size()) throw invalidTree();
        if (rootId != null && !nodes.containsKey(rootId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "COMMON_NOT_FOUND", "分类不存在");
        }
        Map<Long, CategoryTreeVO> views = new HashMap<>();
        for (int index = traversal.size() - 1; index >= 0; index--) {
            CategoryEntity row = traversal.get(index);
            List<CategoryTreeVO> nested = children.getOrDefault(row.getId(), List.of()).stream()
                    .map(child -> views.get(child.getId())).toList();
            views.put(row.getId(), new CategoryTreeVO(row.getId(), row.getParentId(), row.getName(),
                    row.getSortOrder(), row.getStatus(), row.getVersion(), nested));
        }
        return rootId == null ? children.getOrDefault(null, List.of()).stream()
                .map(row -> views.get(row.getId())).toList() : List.of(views.get(rootId));
    }

    private BusinessException invalidTree() {
        return new BusinessException(HttpStatus.CONFLICT, "CATEGORY_TREE_CONFLICT", "分类树关系异常，请先修复分类数据");
    }
}
