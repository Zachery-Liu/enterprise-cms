package com.zachery.cms.modules.content.category;

import com.zachery.cms.common.exception.BusinessException;
import com.zachery.cms.config.RootConfiguration;
import com.zachery.cms.modules.content.category.dto.CategoryRequests.*;
import com.zachery.cms.modules.content.category.mapper.CategoryMapper;
import com.zachery.cms.modules.content.category.service.CategoryService;
import com.zachery.cms.modules.content.category.vo.CategoryVO;
import com.zachery.cms.security.session.SessionKeys;
import com.zachery.cms.security.session.SessionAuthenticationInterceptor;
import com.zachery.cms.support.ContentTestIdentity;
import com.zachery.cms.support.ContentTestConfiguration;
import com.zachery.cms.support.CategorySelectCounter;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

@org.springframework.test.context.TestPropertySource(locations="classpath:cms-test.properties", properties="cms.db.url=jdbc:h2:mem:a_content;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@SpringJUnitConfig({RootConfiguration.class, ContentTestConfiguration.class})
@ActiveProfiles("test")
class CategoryIntegrationTest {
    @Autowired SessionAuthenticationInterceptor authentication;
    @Autowired CategoryService service;
    @Autowired CategoryMapper mapper;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager transactions;
    @Autowired CategorySelectCounter selects;
    long actor;

    @BeforeEach void seed() {
        RequestContextHolder.resetRequestAttributes();
        db.update("DELETE FROM cms_article_status_history");
        db.update("DELETE FROM cms_article");
        db.update("UPDATE cms_category SET deleted=1,parent_id=NULL");
        db.update("DELETE FROM cms_category");
        db.update("DELETE FROM sys_user");
        db.update("INSERT INTO sys_user(username,password_hash,display_name) VALUES ('category-actor','not-used','分类维护人')");
        actor = db.queryForObject("SELECT id FROM sys_user WHERE username='category-actor'", Long.class);
        authenticate();
    }
    @AfterEach void clearIdentity() { RequestContextHolder.resetRequestAttributes(); }

    @Test void createsThreeLevelsFillsAuditAndSupportsRootMoveAndStableOrdering() {
        var root = create(null, "  内容中心　");
        var child = create(root.id(), "新闻");
        var leaf = create(child.id(), "公告");
        assertThat(root.name()).isEqualTo("内容中心");
        assertThat(root.status()).isEqualTo("ENABLED");
        assertThat(root.version()).isZero();
        var entity = mapper.selectById(root.id());
        assertThat(entity.getCreatedBy()).isEqualTo(actor);
        assertThat(entity.getUpdatedBy()).isEqualTo(actor);
        assertThat(entity.getCreatedAt()).isNotNull();
        selects.reset();
        var tree = service.getTree(null);
        assertThat(selects.count()).isOne();
        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).children().get(0).children().get(0).id()).isEqualTo(leaf.id());
        assertThat(service.getTree(child.id())).singleElement().satisfies(node -> assertThat(node.id()).isEqualTo(child.id()));
        var moved = service.move(child.id(), new Move(null, child.version()));
        assertThat(moved.parentId()).isNull();
        assertThat(service.getTree(null)).hasSize(2);
        var sorted = service.changeSort(child.id(), new Sort(9, moved.version()));
        assertThat(sorted.version()).isEqualTo(2);
        assertThat(service.getTree(null)).extracting(node -> node.id()).containsExactly(root.id(), child.id());
    }

    @Test void rejectsSelfDescendantMissingAndDisabledParentsWithoutChangingRows() {
        var root = create(null, "根"); var child = create(root.id(), "子"); var leaf = create(child.id(), "叶");
        conflict(() -> service.move(root.id(), new Move(root.id(), 0)));
        conflict(() -> service.move(root.id(), new Move(leaf.id(), 0)));
        notFound(() -> service.move(child.id(), new Move(Long.MAX_VALUE, 0)));
        service.changeStatus(root.id(), new ChangeStatus("DISABLED", 0));
        conflict(() -> service.create(new Create(child.id(), "新分类", 0)));
        conflict(() -> service.changeStatus(leaf.id(), new ChangeStatus("ENABLED", 0)));
        assertThat(service.get(root.id()).parentId()).isNull();
        assertThat(service.get(child.id()).parentId()).isEqualTo(root.id());
        assertThat(service.get(leaf.id()).version()).isZero();
    }

    @Test void enforcesSiblingNamesForCreateRenameAndMoveButAllowsOtherParentsAndReuse() {
        var root = create(null, "News");
        conflict(() -> service.create(new Create(null, "news", null)));
        var other = create(null, "另一个根"); var child = create(other.id(), "News");
        conflict(() -> service.move(child.id(), new Move(null, 0)));
        conflict(() -> service.rename(other.id(), new Rename("News", 0)));
        service.delete(child.id(), 0);
        assertThat(create(other.id(), "News").id()).isNotEqualTo(child.id());
        var recreated = create(other.id(), "重复删除"); service.delete(recreated.id(), 0);
        recreated = create(other.id(), "重复删除"); service.delete(recreated.id(), 0);
        assertThat(create(other.id(), "重复删除").version()).isZero();
        assertThat(service.get(root.id()).name()).isEqualTo("News");
    }

    @Test void staleVersionsFailAndLogicalDeleteHidesRowsButPreservesAudit() {
        var root = create(null, "可改");
        var renamed = service.rename(root.id(), new Rename("已改", 0));
        assertThat(renamed.version()).isOne();
        conflict(() -> service.rename(root.id(), new Rename("旧请求", 0)));
        conflict(() -> service.delete(root.id(), 0));
        service.delete(root.id(), 1);
        assertThat(mapper.selectById(root.id())).isNull();
        assertThat(service.getTree(null)).isEmpty();
        notFound(() -> service.get(root.id()));
        notFound(() -> service.getTree(root.id()));
        assertThat(db.queryForObject("SELECT deleted FROM cms_category WHERE id=?", Integer.class, root.id())).isOne();
        assertThat(db.queryForObject("SELECT updated_by FROM cms_category WHERE id=?", Long.class, root.id())).isEqualTo(actor);
    }

    @Test void rejectsDeletionWithChildrenOrAnyLiveArticleButDoesNotOfflinePublishedArticles() {
        var root = create(null, "根"); var child = create(root.id(), "子");
        conflict(() -> service.delete(root.id(), 0));
        db.update("INSERT INTO cms_article(category_id,author_id,title,content,status,created_by,updated_by) VALUES (?,?,?,'正文','PUBLISHED',?,?)",
                child.id(), actor, "历史文章", actor, actor);
        service.changeStatus(child.id(), new ChangeStatus("DISABLED", 0));
        assertThat(db.queryForObject("SELECT status FROM cms_article", String.class)).isEqualTo("PUBLISHED");
        conflict(() -> service.delete(child.id(), 1));
        db.update("UPDATE cms_article SET deleted=1");
        service.delete(child.id(), 1);
        service.delete(root.id(), 0);
        assertThat(service.getTree(null)).isEmpty();
    }

    @Test void requiresRealIdentityAndValidatesCommandsThroughSpringMethodValidation() {
        RequestContextHolder.resetRequestAttributes();
        assertThatThrownBy(() -> create(null, "匿名"))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getHttpStatus().value()).isEqualTo(401));
        authenticate();
        for (Create request : List.of(new Create(null, "　 ", 0), new Create(null, "长".repeat(65), 0),
                new Create(-1L, "坏父级", 0), new Create(null, "坏顺序", -1))) {
            assertThatThrownBy(() -> service.create(request)).isInstanceOf(ConstraintViolationException.class);
        }
        var root = create(null, "有效");
        assertThatThrownBy(() -> service.changeStatus(root.id(), new ChangeStatus("BROKEN", 0)))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() -> service.delete(root.id(), null)).isInstanceOf(ConstraintViolationException.class);
    }

    @Test void outerTransactionRollbackRestoresInsertAndVersionAndAvailabilityRequiresTransaction() {
        var root = create(null, "事务分类");
        var transaction = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> transaction.execute(status -> {
            service.rename(root.id(), new Rename("回滚名称", 0));
            create(null, "回滚新增");
            throw new IllegalStateException("injected failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(service.get(root.id()).name()).isEqualTo("事务分类");
        assertThat(service.get(root.id()).version()).isZero();
        assertThat(service.getTree(null)).hasSize(1);
        assertThatThrownBy(() -> service.requireAvailableForWrite(root.id()))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        transaction.executeWithoutResult(status -> assertThat(service.requireAvailableForWrite(root.id()).id()).isEqualTo(root.id()));
    }

    @Test void concurrentDuplicateRootsHaveExactlyOneWinnerEvenFromEmptyTree() throws Exception {
        assertThat(race(() -> create(null, "并发根"), () -> create(null, "并发根")))
                .containsExactlyInAnyOrder(200, 409);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM cms_category WHERE deleted=0", Integer.class)).isOne();
    }

    @Test void concurrentCrossMovesCannotCreateCycle() throws Exception {
        var first = create(null, "甲"); var second = create(null, "乙");
        assertThat(race(() -> service.move(first.id(), new Move(second.id(), 0)),
                () -> service.move(second.id(), new Move(first.id(), 0)))).containsExactlyInAnyOrder(200, 409);
        assertThat(service.getTree(null)).hasSize(1);
        assertThat(service.getTree(null).get(0).children()).hasSize(1);
    }

    @Test void concurrentSameVersionEditsHaveExactlyOneWinner() throws Exception {
        var root = create(null, "旧名");
        assertThat(race(() -> service.rename(root.id(), new Rename("甲", 0)),
                () -> service.rename(root.id(), new Rename("乙", 0)))).containsExactlyInAnyOrder(200, 409);
        assertThat(service.get(root.id()).version()).isOne();
    }

    @Test void concurrentArticleBindingAndCategoryDeletionCannotLeaveLiveArticleOnDeletedCategory() throws Exception {
        var category = create(null, "引用竞争");
        var transaction = new TransactionTemplate(transactions);
        var results = race(() -> { service.delete(category.id(), 0); return null; },
                () -> transaction.execute(status -> {
                    service.requireAvailableForWrite(category.id());
                    return db.update("INSERT INTO cms_article(category_id,author_id,title,content,created_by,updated_by) VALUES (?,?,'引用','正文',?,?)",
                            category.id(), actor, actor, actor);
                }));
        assertThat(results.stream().filter(result -> result == 200).count()).isOne();
        assertThat(results.stream().filter(result -> result != 200).findFirst().orElseThrow()).isIn(404, 409);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM cms_article a JOIN cms_category c ON c.id=a.category_id WHERE a.deleted=0 AND c.deleted=1",
                Integer.class)).isZero();
    }

    private List<Integer> race(Supplier<?> first, Supplier<?> second) throws Exception {
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> one = executor.submit(() -> attempt(start, first));
            Future<Integer> two = executor.submit(() -> attempt(start, second));
            start.countDown();
            return List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }
    private int attempt(CountDownLatch start, Supplier<?> work) throws InterruptedException {
        start.await(); authenticate();
        try { work.get(); return 200; }
        catch (BusinessException error) { return error.getHttpStatus().value(); }
        finally { RequestContextHolder.resetRequestAttributes(); }
    }
    private void authenticate() {
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute(SessionKeys.CURRENT_USER_ID, actor);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request)); ContentTestIdentity.authenticate(authentication);
    }
    private CategoryVO create(Long parentId, String name) { return service.create(new Create(parentId, name, null)); }
    private void conflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable work) {
        assertThatThrownBy(work).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getHttpStatus().value()).isEqualTo(409));
    }
    private void notFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable work) {
        assertThatThrownBy(work).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getHttpStatus().value()).isEqualTo(404));
    }
}
