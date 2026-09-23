package com.zachery.cms.modules.content.category.service;

import com.zachery.cms.common.context.CurrentUserContext;
import com.zachery.cms.common.exception.BusinessException;
import com.zachery.cms.modules.content.category.dto.CategoryRequests.*;
import com.zachery.cms.modules.content.category.entity.CategoryEntity;
import com.zachery.cms.modules.content.category.mapper.CategoryMapper;
import com.zachery.cms.modules.content.category.vo.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.validation.annotation.Validated;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Supplier;

/** Internal application services. HTTP exposure awaits B's permission and audit integration. */
@Service
@Validated
@Transactional(readOnly = true)
public class CategoryService {
    private final CategoryMapper categories;
    private final CurrentUserContext users;
    private final CategoryTreeAssembler trees;

    public CategoryService(CategoryMapper categories, CurrentUserContext users, CategoryTreeAssembler trees) {
        this.categories = categories;
        this.users = users;
        this.trees = trees;
    }

    public List<CategoryTreeVO> getTree(@Positive Long rootId) {
        users.getRequiredUser();
        return trees.assemble(categories.selectVisibleTreeRows(), rootId);
    }

    public CategoryVO get(@NotNull @Positive Long id) {
        users.getRequiredUser();
        CategoryEntity row = categories.selectById(id);
        if (row == null) throw notFound();
        return CategoryVO.from(row);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CategoryVO create(@NotNull @Valid Create request) {
        Long actor = users.getRequiredUser().userId();
        return write(() -> {
            Map<Long, CategoryEntity> snapshot = lockSnapshot();
            requireUsableAncestors(snapshot, request.parentId(), null);
            requireUniqueName(request.parentId(), request.name(), null);
            var row = new CategoryEntity();
            row.setParentId(request.parentId());
            row.setName(request.name());
            row.setSortOrder(request.sortOrder());
            row.setStatus("ENABLED");
            row.setVersion(0);
            row.setDeleted(0);
            row.setCreatedBy(actor);
            row.setUpdatedBy(actor);
            if (categories.insert(row) != 1) throw conflict("分类创建失败");
            return CategoryVO.from(row);
        });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CategoryVO rename(@NotNull @Positive Long id, @NotNull @Valid Rename request) {
        Long actor = users.getRequiredUser().userId();
        return write(() -> {
            CategoryEntity row = requireVersion(lockSnapshot(), id, request.version());
            requireUniqueName(row.getParentId(), request.name(), id);
            row.setName(request.name());
            return update(row, actor);
        });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CategoryVO move(@NotNull @Positive Long id, @NotNull @Valid Move request) {
        Long actor = users.getRequiredUser().userId();
        return write(() -> {
            Map<Long, CategoryEntity> snapshot = lockSnapshot();
            CategoryEntity row = requireVersion(snapshot, id, request.version());
            requireUsableAncestors(snapshot, request.parentId(), id);
            requireUniqueName(request.parentId(), row.getName(), id);
            row.setParentId(request.parentId());
            return update(row, actor);
        });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CategoryVO changeSort(@NotNull @Positive Long id, @NotNull @Valid Sort request) {
        Long actor = users.getRequiredUser().userId();
        return write(() -> {
            CategoryEntity row = requireVersion(lockSnapshot(), id, request.version());
            row.setSortOrder(request.sortOrder());
            return update(row, actor);
        });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CategoryVO changeStatus(@NotNull @Positive Long id, @NotNull @Valid ChangeStatus request) {
        Long actor = users.getRequiredUser().userId();
        return write(() -> {
            Map<Long, CategoryEntity> snapshot = lockSnapshot();
            CategoryEntity row = requireVersion(snapshot, id, request.version());
            if ("ENABLED".equals(request.status())) requireUsableAncestors(snapshot, row.getParentId(), id);
            row.setStatus(request.status());
            return update(row, actor);
        });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(@NotNull @Positive Long id, @NotNull @PositiveOrZero Integer version) {
        Long actor = users.getRequiredUser().userId();
        write(() -> {
            Map<Long, CategoryEntity> snapshot = lockSnapshot();
            CategoryEntity row = requireVersion(snapshot, id, version);
            if (snapshot.values().stream().anyMatch(child -> id.equals(child.getParentId()))) {
                throw conflict("分类仍有子分类，不能删除");
            }
            if (!categories.findArticleReferencesForUpdate(id).isEmpty()) throw conflict("分类仍有文章引用，不能删除");
            row.setDeleted(1);
            update(row, actor);
            return null;
        });
    }

    /** Article writes must invoke this before accessing article rows, in their existing transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public CategoryVO requireAvailableForWrite(@NotNull @Positive Long id) {
        users.getRequiredUser();
        return write(() -> {
            Map<Long, CategoryEntity> snapshot = lockSnapshot();
            requireUsableAncestors(snapshot, id, null);
            return CategoryVO.from(snapshot.get(id));
        });
    }

    /** Batch preflight holds the same category lock once and reports invalid category IDs. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long,String> unavailableForWrites(@NotNull java.util.Collection<@NotNull @Positive Long> ids) {
        users.getRequiredUser();
        return write(() -> {
            var snapshot=lockSnapshot();
            Map<Long,String> failures=new HashMap<>();
            for(Long id:new HashSet<>(ids)) {
                try {requireUsableAncestors(snapshot,id,null);}
                catch(BusinessException error){failures.put(id,error.getMessage());}
            }
            return Map.copyOf(failures);
        });
    }

    private Map<Long, CategoryEntity> lockSnapshot() {
        categories.lockAllRows();
        Map<Long, CategoryEntity> snapshot = new HashMap<>();
        for (CategoryEntity row : categories.selectAllForUpdate()) {
            if (row.getDeleted() == 0) snapshot.put(row.getId(), row);
        }
        return snapshot;
    }

    /** Deletion may clean up articles in disabled categories, while retaining the common lock order. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForArticleWrite() {
        users.getRequiredUser();
        write(() -> { lockSnapshot(); return null; });
    }

    private void requireUsableAncestors(Map<Long, CategoryEntity> snapshot, Long parentId, Long movingId) {
        Set<Long> visited = new HashSet<>();
        Long cursor = parentId;
        while (cursor != null) {
            if (cursor.equals(movingId) || !visited.add(cursor)) throw conflict("分类不能移动到自身或后代分类");
            CategoryEntity parent = snapshot.get(cursor);
            if (parent == null) throw notFound();
            if (!"ENABLED".equals(parent.getStatus())) throw conflict("分类或上级分类已停用");
            cursor = parent.getParentId();
        }
    }

    private void requireUniqueName(Long parentId, String name, Long excludeId) {
        if (!categories.findSiblingForUpdate(parentId, name, excludeId).isEmpty()) throw conflict("同级分类名称已存在");
    }

    private CategoryEntity requireVersion(Map<Long, CategoryEntity> snapshot, Long id, Integer version) {
        CategoryEntity row = snapshot.get(id);
        if (row == null) throw notFound();
        if (!row.getVersion().equals(version) || version == Integer.MAX_VALUE) throw conflict("分类版本已变化，请刷新后重试");
        return row;
    }

    private CategoryVO update(CategoryEntity row, Long actor) {
        row.setUpdatedBy(actor);
        row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        if (categories.updateCategory(row) != 1) throw conflict("分类版本已变化，请刷新后重试");
        row.setVersion(row.getVersion() + 1);
        return CategoryVO.from(row);
    }

    private <T> T write(Supplier<T> operation) {
        try { return operation.get(); }
        catch (DuplicateKeyException exception) { throw conflict("同级分类名称已存在"); }
        catch (PessimisticLockingFailureException exception) { throw conflict("分类正在被修改，请刷新后重试"); }
    }
    private BusinessException conflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, "COMMON_CONFLICT", message);
    }
    private BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "COMMON_NOT_FOUND", "分类不存在");
    }
}
