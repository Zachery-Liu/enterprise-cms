package com.zachery.cms.modules.content.category;

import com.zachery.cms.common.exception.BusinessException;
import com.zachery.cms.modules.content.category.entity.CategoryEntity;
import com.zachery.cms.modules.content.category.service.CategoryTreeAssembler;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CategoryTreeAssemblerTest {
    private final CategoryTreeAssembler assembler = new CategoryTreeAssembler();

    @Test void sortsEveryLevelKeepsDisabledNodesAndReturnsRequestedSubtree() {
        var tree = assembler.assemble(List.of(row(3, 1L, 9), row(2, null, 0), row(1, null, 0), row(4, 1L, 1)), null);
        assertThat(tree).extracting(node -> node.id()).containsExactly(1L, 2L);
        assertThat(tree.get(0).children()).extracting(node -> node.id()).containsExactly(4L, 3L);
        assertThat(tree.get(1).children()).isEmpty();
        assertThat(tree.get(0).status()).isEqualTo("DISABLED");
        assertThat(assembler.assemble(List.of(row(1, null, 0), row(2, 1L, 0)), 2L))
                .singleElement().satisfies(node -> { assertThat(node.id()).isEqualTo(2L); assertThat(node.children()).isEmpty(); });
        assertThatThrownBy(() -> tree.get(0).children().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void handlesEmptyTreeAndMissingRoot() {
        assertThat(assembler.assemble(List.of(), null)).isEmpty();
        assertThatThrownBy(() -> assembler.assemble(List.of(), 1L)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getHttpStatus().value()).isEqualTo(404));
    }

    @Test void rejectsOrphansSelfCyclesDisconnectedCyclesAndDuplicateIds() {
        for (List<CategoryEntity> rows : List.of(List.of(row(1, 99L, 0)), List.of(row(1, 1L, 0)),
                List.of(row(1, null, 0), row(2, 3L, 0), row(3, 2L, 0)), List.of(row(1, null, 0), row(1, null, 0)))) {
            assertThatThrownBy(() -> assembler.assemble(rows, null)).isInstanceOfSatisfying(BusinessException.class,
                    error -> assertThat(error.getCode()).isEqualTo("CATEGORY_TREE_CONFLICT"));
        }
    }

    @Test void assemblesDeepTreesWithoutRecursiveStackGrowth() {
        List<CategoryEntity> rows = new ArrayList<>();
        for (long id = 1; id <= 5000; id++) rows.add(row(id, id == 1 ? null : id - 1, 0));
        var node = assembler.assemble(rows, null).get(0);
        for (long id = 1; id < 5000; id++) { assertThat(node.id()).isEqualTo(id); node = node.children().get(0); }
        assertThat(node.id()).isEqualTo(5000L);
        assertThat(node.children()).isEmpty();
    }

    private CategoryEntity row(long id, Long parentId, int sortOrder) {
        var row = new CategoryEntity();
        row.setId(id); row.setParentId(parentId); row.setSortOrder(sortOrder);
        row.setName("分类" + id); row.setStatus("DISABLED"); row.setVersion(0); row.setDeleted(0);
        return row;
    }
}
