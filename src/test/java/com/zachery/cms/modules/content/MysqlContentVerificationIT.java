package com.zachery.cms.modules.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zachery.cms.common.exception.BusinessException;
import com.zachery.cms.config.RootConfiguration;
import com.zachery.cms.config.WebConfiguration;
import com.zachery.cms.modules.content.article.dto.*;
import com.zachery.cms.modules.content.article.service.*;
import com.zachery.cms.modules.content.article.vo.ArticleListVO;
import com.zachery.cms.modules.content.category.dto.CategoryRequests.*;
import com.zachery.cms.modules.content.category.service.CategoryService;
import com.zachery.cms.modules.content.publication.*;
import com.zachery.cms.security.session.*;
import com.zachery.cms.support.ContentTestIdentity;
import com.zachery.cms.support.CategorySelectCounter;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.Validator;
import org.springframework.web.context.request.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

/**
 * Explicit opt-in against a preinitialized, dedicated LOCAL MySQL verification schema.
 * No H2 schema, schema reset, B identity replacement or application configuration is installed here.
 * Run scripts/init_content_mysql.py --initialize first. This IT is excluded from normal unit runs.
 */
@EnabledIfEnvironmentVariable(named = "CMS_MYSQL_CONTENT_VERIFY", matches = "true")
@TestPropertySource(properties = {
        "cms.db.url=${CMS_MYSQL_TEST_URL}", "cms.db.username=${CMS_MYSQL_TEST_USER}",
        "cms.db.password=${CMS_MYSQL_TEST_PASSWORD}", "cms.db.driver=com.mysql.cj.jdbc.Driver"
})
@SpringJUnitConfig({RootConfiguration.class, MysqlContentVerificationIT.JsonConfiguration.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MysqlContentVerificationIT {
    @Configuration(proxyBeanMethods = false)
    static class JsonConfiguration {
        @Bean static CategorySelectCounter mysqlSelectCounter() { return new CategorySelectCounter(); }
        @Bean ObjectMapper verificationJson(Validator validator) {
            return new WebConfiguration(validator).objectMapper();
        }
    }

    @Autowired JdbcTemplate db;
    @Autowired CategoryService categories;
    @Autowired ArticleService articles;
    @Autowired ArticleQueryService pages;
    @Autowired ArticleLifecycleService lifecycle;
    @Autowired PublicationBatchService batches;
    @Autowired PublicationQueryService publication;
    @Autowired SessionAuthenticationInterceptor authentication;
    @Autowired PlatformTransactionManager transactions;
    @Autowired CategorySelectCounter sql;
    long category;

    @BeforeAll void requireExplicitDatabaseAndVersionBeforeAnyWrite() {
        String schema = System.getenv("CMS_MYSQL_TEST_SCHEMA");
        assertThat(schema).matches("cms_a_verify_[0-9]{8}");
        var address = java.net.URI.create(System.getenv("CMS_MYSQL_TEST_URL").substring("jdbc:".length()));
        assertThat(address.getScheme()).isEqualTo("mysql");
        assertThat(address.getHost()).isEqualTo("127.0.0.1");
        assertThat(address.getPort()).isBetween(1, 65535);
        assertThat(address.getPath()).isEqualTo("/" + schema);
        assertThat(address.getUserInfo()).isNull();
        assertThat(db.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(schema);
        String prefix = Optional.ofNullable(System.getenv("CMS_MYSQL_TEST_VERSION_PREFIX")).orElse("8.0.");
        assertThat(db.queryForObject("SELECT VERSION()", String.class)).startsWith(prefix);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND ENGINE='InnoDB'", Integer.class)).isEqualTo(9);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id BETWEEN 1 AND 4", Integer.class)).isEqualTo(4);
        System.out.println("Content verification: MySQL " + db.queryForObject("SELECT VERSION()", String.class) + ", schema=" + schema);
    }

    @BeforeEach void identityAndIndependentCategory() {
        authenticate();
        category = categories.create(new Create(null, name(), 0)).id();
    }
    @AfterEach void clearIdentity() { RequestContextHolder.resetRequestAttributes(); }

    @Test @Order(1) void realForeignKeysChecksAndGeneratedSiblingUniquenessAreEnforced() {
        String insert = "INSERT INTO cms_category(parent_id,name,created_by,updated_by) VALUES (?,?,1,1)";
        mysqlRejects(1452, () -> db.update(insert, Long.MAX_VALUE, name()));
        mysqlRejects(3819, () -> db.update(insert, null, " "));
        mysqlRejects(3819, () -> db.update("UPDATE cms_category SET status='BROKEN' WHERE id=?", category));
        mysqlRejects(3819, () -> db.update("UPDATE cms_category SET deleted=2 WHERE id=?", category));
        String existing = categories.get(category).name();
        mysqlRejects(1062, () -> db.update(insert, null, existing.toUpperCase(Locale.ROOT)));
        mysqlRejects(1452, () -> db.update("INSERT INTO cms_article(category_id,author_id,title,content,created_by,updated_by) VALUES (?,1,'t','body',1,1)", Long.MAX_VALUE));
        long id = draft();
        for (String assignment : List.of("title=' '", "content=' '", "status='BROKEN'", "deleted=2",
                "status='PUBLISHED',published_at=NULL", "status='REJECTED',review_comment=NULL", "author_id=" + Long.MAX_VALUE)) {
            mysqlRejects(assignment.startsWith("author_id=") ? 1452 : 3819,
                    () -> db.update("UPDATE cms_article SET " + assignment + " WHERE id=?", id));
        }
        for (String assignment : List.of("action='BROKEN'", "from_status='BROKEN'", "to_status='BROKEN'", "operator_id=" + Long.MAX_VALUE)) {
            mysqlRejects(assignment.startsWith("operator_id=") ? 1452 : 3819,
                    () -> db.update("UPDATE cms_article_status_history SET " + assignment + " WHERE article_id=?", id));
        }
        mysqlRejects(1451, () -> db.update("DELETE FROM cms_article WHERE id=?", id));
        assertThat(articles.detail(id).status()).isEqualTo("DRAFT");
    }

    @Test @Order(2) void logicalDeletionAllowsRepeatedSiblingNameReuseAndTreeMovesStayAcyclic() {
        long child = categories.create(new Create(category, "Repeated", 0)).id();
        conflict(() -> categories.create(new Create(category, "repeated", 0)));
        long initialChild = child;
        conflict(() -> categories.move(category, new Move(initialChild, 0)));
        conflict(() -> categories.move(category, new Move(category, 0)));
        for (int index = 0; index < 3; index++) {
            categories.delete(child, 0);
            child = categories.create(new Create(category, "Repeated", 0)).id();
        }
        assertThat(categories.getTree(category).get(0).children()).hasSize(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM cms_category WHERE parent_id=? AND deleted=1", Integer.class, category)).isEqualTo(3);
    }

    @Test @Order(3) void draftCrudUsesRealIdentitySanitizesAndPreservesHistoryOnLogicalDelete() {
        var created = articles.create(new ArticleRequests.Create(" 标题 ", "摘要", "<p onclick='bad()'>正文<script>bad()</script></p>", category));
        assertThat(created.authorId()).isEqualTo(1L);
        assertThat(created.authorDisplayName()).isEqualTo("系统管理员");
        assertThat(created.content()).isEqualTo("<p>正文</p>");
        assertThat(created.createdAt()).isNotNull();
        assertThat(db.queryForObject("SELECT created_by FROM cms_article WHERE id=?", Long.class, created.id())).isEqualTo(1L);
        var updated = articles.update(created.id(), new ArticleRequests.Update("新标题", null, "新正文", category, 0));
        assertThat(updated.version()).isOne();
        assertThat(updated.status()).isEqualTo("DRAFT");
        conflict(() -> articles.update(created.id(), new ArticleRequests.Update("过期", null, "正文", category, 0)));
        conflict(() -> articles.delete(created.id(), 0));
        articles.delete(created.id(), 1);
        missing(() -> articles.detail(created.id()));
        assertThat(db.queryForObject("SELECT deleted FROM cms_article WHERE id=?", Integer.class, created.id())).isOne();
        assertThat(actions(created.id())).containsExactly("CREATE");
    }

    @Test @Order(4) void paginationHasStableTiesLiteralLikeEscapingAndBatchAuthorProjection() {
        List<Long> ids = new ArrayList<>();
        for (int index = 0; index < 12; index++) ids.add(articles.create(new ArticleRequests.Create("literal%_!" + index, null, "正文", category)).id());
        db.update("UPDATE cms_article SET updated_at='2026-10-30 00:00:00.123' WHERE category_id=?", category);
        sql.reset();
        var first = pages.page(query(1, 5, "literal%_!"));
        assertThat(sql.statements().stream().filter(statement -> statement.contains(" from cms_article ")
                || (statement.contains(" from sys_user ") && statement.contains(" in (")
                && !statement.contains("join sys_user_role"))).count()).isEqualTo(3);
        var second = pages.page(query(2, 5, "literal%_!"));
        assertThat(first.total()).isEqualTo(12);
        assertThat(first.records()).extracting(ArticleListVO::id).containsExactlyElementsOf(ids.subList(0, 5));
        assertThat(second.records()).extracting(ArticleListVO::id).containsExactlyElementsOf(ids.subList(5, 10));
        assertThat(first.records()).allSatisfy(row -> {
            assertThat(row.categoryId()).isEqualTo(category);
            assertThat(row.authorDisplayName()).isEqualTo("系统管理员");
        });
        assertThat(pages.page(query(4, 5, "literal%_!")).records()).isEmpty();
        assertThat(pages.page(query(1, 5, "literalXX")).total()).isZero();
    }

    @Test @Order(5) void completeLifecycleWritesOrderedHistoryAndPublicQueriesOnlyExposePublishedArticles() {
        long id = draft();
        missing(() -> publication.publicDetail(id));
        transition(id, ArticleAction.SUBMIT, 0, null);
        transition(id, ArticleAction.REJECT, 1, "补充内容");
        assertThat(articles.detail(id).reviewerId()).isEqualTo(1L);
        articles.update(id, new ArticleRequests.Update("修订标题", null, "修订正文", category, 2));
        transition(id, ArticleAction.SUBMIT, 3, null);
        transition(id, ArticleAction.APPROVE, 4, "通过");
        transition(id, ArticleAction.PUBLISH, 5, null);
        assertThat(publication.publicDetail(id).content()).isEqualTo("修订正文");
        RequestContextHolder.resetRequestAttributes();
        assertThat(publication.publicPage(new PublicationPageQuery(1, 20, null, category)).records()).hasSize(1);
        authenticate();
        transition(id, ArticleAction.OFFLINE, 6, "撤回");
        missing(() -> publication.publicDetail(id));
        transition(id, ArticleAction.REOPEN, 7, null);
        assertThat(articles.detail(id).status()).isEqualTo("DRAFT");
        assertThat(articles.detail(id).publishedAt()).isNull();
        assertThat(actions(id)).containsExactly("CREATE", "SUBMIT", "REJECT", "SUBMIT", "APPROVE", "PUBLISH", "OFFLINE", "REOPEN");
        assertThat(publication.history(id, 1, 20).total()).isEqualTo(8);
    }

    @Test @Order(6) void disabledCategoryBlocksSaveSubmitAndPublishWithoutChangingHistory() {
        long draft = draft(), ready = approved();
        categories.changeStatus(category, new ChangeStatus("DISABLED", 0));
        conflict(this::draft);
        conflict(() -> articles.update(draft, new ArticleRequests.Update("t", null, "body", category, 0)));
        conflict(() -> transition(draft, ArticleAction.SUBMIT, 0, null));
        conflict(() -> transition(ready, ArticleAction.PUBLISH, 2, null));
        assertApproved(ready);
        assertThat(actions(draft)).containsExactly("CREATE");
        assertThat(actions(ready)).containsExactly("CREATE", "SUBMIT", "APPROVE");
        articles.delete(draft, 0);
    }

    @Test @Order(7) void publishesFiftyInOneTransactionAndReturnsRequestOrder() {
        List<Long> ids = new ArrayList<>();
        for (int index = 0; index < 50; index++) ids.add(approved());
        Collections.reverse(ids);
        var result = batches.publish(command(ids.toArray(Long[]::new)));
        assertThat(result.count()).isEqualTo(50);
        assertThat(result.items()).extracting(TransitionResult::id).containsExactlyElementsOf(ids);
        assertThat(result.items()).allSatisfy(row -> {
            assertThat(row.version()).isEqualTo(3);
            assertThat(row.status()).isEqualTo("PUBLISHED");
        });
        assertThat(result.items()).extracting(TransitionResult::updatedAt).containsOnly(result.items().get(0).updatedAt());
        assertThat(publishCount()).isEqualTo(50);
    }

    @Test @Order(8) void invalidBatchPreflightRejectsAllRowsIncludingValidItems() {
        long good = approved(), stale = approved(), draft = draft(), deleted = draft();
        articles.delete(deleted, 0);
        var request = new BatchPublishCommand(List.of(new BatchPublishCommand.Item(good, 2),
                new BatchPublishCommand.Item(stale, 1), new BatchPublishCommand.Item(draft, 0),
                new BatchPublishCommand.Item(deleted, 1), new BatchPublishCommand.Item(Long.MAX_VALUE, 0)));
        assertThatThrownBy(() -> batches.publish(request)).isInstanceOfSatisfying(BatchPublicationException.class,
                error -> assertThat(error.getFailures()).extracting(BatchPublicationException.Failure::id)
                        .containsExactly(stale, draft, deleted, Long.MAX_VALUE));
        assertThatThrownBy(() -> batches.publish(command(good, good))).isInstanceOf(BatchPublicationException.class);
        assertThatThrownBy(() -> batches.publish(new BatchPublishCommand(Collections.nCopies(51, new BatchPublishCommand.Item(good, 2)))))
                .isInstanceOf(ConstraintViolationException.class);
        assertApproved(good);
        assertApproved(stale);
        assertThat(publishCount()).isZero();
    }

    @Test @Order(9) void secondHistoryInsertFailureRollsBackBothArticleStatesAndHistoriesThenRetrySucceeds() {
        long first = approved(), second = approved();
        String trigger = "verify_history_" + UUID.randomUUID().toString().replace("-", "");
        db.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON cms_article_status_history FOR EACH ROW BEGIN "
                + "IF NEW.article_id=" + second + " AND NEW.action='PUBLISH' THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected second history failure'; END IF; END");
        try {
            assertThatThrownBy(() -> batches.publish(command(second, first)))
                    .isInstanceOfSatisfying(BatchPublicationException.class, error -> {
                        assertThat(error.getFailures().get(0).id()).isEqualTo(second);
                        assertThat(error.getFailures().get(0).code()).isEqualTo("ARTICLE_BATCH_WRITE_FAILED");
                        assertThat(error.getMessage()).doesNotContain("SQL", "INSERT", "trigger");
                    });
        } finally { db.execute("DROP TRIGGER " + trigger); }
        assertApproved(first);
        assertApproved(second);
        assertThat(actions(first)).containsExactly("CREATE", "SUBMIT", "APPROVE");
        assertThat(actions(second)).containsExactly("CREATE", "SUBMIT", "APPROVE");
        assertThat(publishCount()).isZero();
        batches.publish(command(second, first));
        assertThat(publishCount()).isEqualTo(2);
    }

    @Test @Order(10) void failingCreateHistoryAlsoRollsBackArticleInsert() {
        String trigger = "verify_create_" + UUID.randomUUID().toString().replace("-", "");
        db.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON cms_article_status_history FOR EACH ROW BEGIN "
                + "IF NEW.action='CREATE' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected create history failure'; END IF; END");
        try { assertThatThrownBy(this::draft).isInstanceOf(org.springframework.dao.DataAccessException.class); }
        finally { db.execute("DROP TRIGGER " + trigger); }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM cms_article WHERE category_id=?", Integer.class, category)).isZero();
    }

    @Test @Order(11) void outerTransactionFailureRollsBackEntireBatchAndRetryCannotDuplicateHistory() {
        long first = approved(), second = approved();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
            batches.publish(command(first, second));
            throw new IllegalStateException("outer failure");
        })).isInstanceOf(IllegalStateException.class);
        assertApproved(first);
        assertApproved(second);
        assertThat(publishCount()).isZero();
        batches.publish(command(first, second));
        assertThatThrownBy(() -> batches.publish(command(first, second))).isInstanceOf(BatchPublicationException.class);
        assertThat(publishCount()).isEqualTo(2);
    }

    @Test @Order(12) void concurrentSiblingCreatesHaveExactlyOneWinner() throws Exception {
        String name = name();
        assertThat(race(() -> categories.create(new Create(category, name, 0)),
                () -> categories.create(new Create(category, name, 0)))).containsExactlyInAnyOrder(200, 409);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM cms_category WHERE parent_id=? AND deleted=0", Integer.class, category)).isOne();
    }

    @Test @Order(13) void concurrentCrossMovesCannotCreateCycle() throws Exception {
        long other = categories.create(new Create(null, name(), 0)).id();
        assertThat(race(() -> categories.move(category, new Move(other, 0)),
                () -> categories.move(other, new Move(category, 0)))).containsExactlyInAnyOrder(200, 409);
        assertThat(categories.get(category).parentId() == null || categories.get(other).parentId() == null).isTrue();
    }

    @Test @Order(14) void concurrentSameVersionEditsHaveOneWinner() throws Exception {
        long id = draft();
        assertThat(race(() -> articles.update(id, new ArticleRequests.Update("first", null, "body", category, 0)),
                () -> articles.update(id, new ArticleRequests.Update("second", null, "body", category, 0))))
                .containsExactlyInAnyOrder(200, 409);
        assertThat(articles.detail(id).version()).isOne();
        assertThat(actions(id)).containsExactly("CREATE");
    }

    @Test @Order(15) void concurrentOverlappingBatchesNeverPartiallyPublishLosingBatch() throws Exception {
        long first = approved(), shared = approved(), last = approved();
        assertThat(race(() -> batches.publish(command(shared, first)), () -> batches.publish(command(last, shared))))
                .containsExactlyInAnyOrder(200, 409);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM cms_article WHERE category_id=? AND status='PUBLISHED' AND version=3", Integer.class, category)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM cms_article WHERE category_id=? AND status='APPROVED' AND version=2", Integer.class, category)).isOne();
        assertThat(publishCount()).isEqualTo(2);
    }

    @Test @Order(16) void concurrentArticleCreateAndCategoryDeletePreserveReferentialBusinessRule() throws Exception {
        var result = race(() -> { categories.delete(category, 0); return null; }, this::draft);
        assertThat(result.stream().filter(code -> code == 200).count()).isOne();
        assertThat(result.stream().filter(code -> code != 200).findFirst().orElseThrow()).isIn(404, 409);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM cms_article a JOIN cms_category c ON c.id=a.category_id WHERE a.category_id=? AND a.deleted=0 AND c.deleted=1", Integer.class, category)).isZero();
    }

    private String name() { return "mysql-" + UUID.randomUUID(); }
    private long draft() { return articles.create(new ArticleRequests.Create("MySQL验证", null, "<p>正文</p>", category)).id(); }
    private long approved() {
        long id = draft();
        transition(id, ArticleAction.SUBMIT, 0, null);
        transition(id, ArticleAction.APPROVE, 1, null);
        return id;
    }
    private TransitionResult transition(long id, ArticleAction action, int version, String comment) {
        return lifecycle.transition(id, action, new TransitionCommand(version, comment));
    }
    private ArticlePageQuery query(int page, int size, String title) {
        return new ArticlePageQuery(page, size, title, category, "DRAFT", 1L, null, null, null, null, "updatedAt", "ASC");
    }
    private List<String> actions(long id) {
        return db.queryForList("SELECT action FROM cms_article_status_history WHERE article_id=? ORDER BY created_at,id", String.class, id);
    }
    private int publishCount() {
        return db.queryForObject("SELECT COUNT(*) FROM cms_article_status_history h JOIN cms_article a ON a.id=h.article_id WHERE a.category_id=? AND h.action='PUBLISH'", Integer.class, category);
    }
    private BatchPublishCommand command(Long... ids) {
        return new BatchPublishCommand(Arrays.stream(ids).map(id -> new BatchPublishCommand.Item(id, 2)).toList());
    }
    private void assertApproved(long id) {
        var row = articles.detail(id);
        assertThat(row.status()).isEqualTo("APPROVED");
        assertThat(row.version()).isEqualTo(2);
        assertThat(row.publishedAt()).isNull();
    }
    private void conflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable work) { error(409, work); }
    private void missing(org.assertj.core.api.ThrowableAssert.ThrowingCallable work) { error(404, work); }
    private void error(int status, org.assertj.core.api.ThrowableAssert.ThrowingCallable work) {
        assertThatThrownBy(work).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.getHttpStatus().value()).isEqualTo(status));
    }
    private void mysqlRejects(int vendorCode, org.assertj.core.api.ThrowableAssert.ThrowingCallable work) {
        // CHECK violations use HY000/3819 and need not translate to DataIntegrityViolationException.
        assertThatThrownBy(work).isInstanceOfSatisfying(DataAccessException.class, failure ->
                assertThat(failure.getMostSpecificCause()).isInstanceOfSatisfying(java.sql.SQLException.class,
                        sql -> assertThat(sql.getErrorCode()).isEqualTo(vendorCode)));
    }
    private List<Integer> race(Supplier<?> first, Supplier<?> second) throws Exception {
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> attempt(start, first));
            var two = pool.submit(() -> attempt(start, second));
            start.countDown();
            return List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
    private int attempt(CountDownLatch start, Supplier<?> work) throws InterruptedException {
        start.await(); authenticate();
        try { work.get(); return 200; }
        catch (BusinessException error) { return error.getHttpStatus().value(); }
        finally { RequestContextHolder.resetRequestAttributes(); }
    }
    private void authenticate() {
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute(SessionKeys.CURRENT_USER_ID, 1L);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        ContentTestIdentity.authenticate(authentication);
    }
}
