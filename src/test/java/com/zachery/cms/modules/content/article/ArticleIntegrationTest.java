package com.zachery.cms.modules.content.article;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zachery.cms.common.exception.BusinessException;
import com.zachery.cms.config.RootConfiguration;
import com.zachery.cms.modules.content.article.dto.ArticleRequests.*;
import com.zachery.cms.modules.content.article.mapper.ArticleMapper;
import com.zachery.cms.modules.content.article.service.ArticleService;
import com.zachery.cms.modules.content.article.vo.ArticleDetailVO;
import com.zachery.cms.modules.content.category.dto.CategoryRequests;
import com.zachery.cms.modules.content.category.service.CategoryService;
import com.zachery.cms.security.session.SessionKeys;
import com.zachery.cms.security.session.SessionAuthenticationInterceptor;
import com.zachery.cms.support.ContentTestIdentity;
import com.zachery.cms.support.ContentTestConfiguration;
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
import static org.assertj.core.api.Assertions.*;

@org.springframework.test.context.TestPropertySource(locations="classpath:cms-test.properties", properties="cms.db.url=jdbc:h2:mem:a_content;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@SpringJUnitConfig({RootConfiguration.class,ContentTestConfiguration.class}) @ActiveProfiles("test")
class ArticleIntegrationTest {
    @Autowired SessionAuthenticationInterceptor authentication;
    @Autowired ArticleService service;
    @Autowired ArticleMapper mapper;
    @Autowired CategoryService categories;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    long actor; long category;
    @BeforeEach void seed() {
        RequestContextHolder.resetRequestAttributes();
        db.update("DELETE FROM cms_article_status_history"); db.update("DELETE FROM cms_article");
        db.update("UPDATE cms_category SET deleted=1,parent_id=NULL"); db.update("DELETE FROM cms_category"); db.update("DELETE FROM sys_user");
        db.update("INSERT INTO sys_user(username,password_hash,display_name) VALUES ('article-actor','unused','文章作者')");
        actor=db.queryForObject("SELECT id FROM sys_user WHERE username='article-actor'",Long.class);
        authenticate(); category=categories.create(new CategoryRequests.Create(null,"文章分类",0)).id();
    }
    @AfterEach void clear() { RequestContextHolder.resetRequestAttributes(); }
    @Test void createsCleanDraftAndHistoryWithRealActorAndUtcResponse() throws Exception {
        var request=json.readValue("{\"title\":\" 标题 \",\"content\":\"<p onclick='bad()'>正文<script>bad()</script></p>\","
                +"\"categoryId\":"+category+",\"authorId\":999,\"status\":\"PUBLISHED\",\"reviewerId\":999,\"version\":80}",Create.class);
        var result=service.create(request);
        assertThat(result.status()).isEqualTo("DRAFT"); assertThat(result.version()).isZero();
        assertThat(result.authorId()).isEqualTo(actor); assertThat(result.authorDisplayName()).isEqualTo("文章作者");
        assertThat(result.title()).isEqualTo("标题"); assertThat(result.content()).isEqualTo("<p>正文</p>");
        assertThat(result.reviewerId()).isNull(); assertThat(result.createdAt()).isNotNull();
        assertThat(json.writeValueAsString(result)).contains("Z\"").doesNotContain("password","deleted","createdBy");
        var row=mapper.selectById(result.id()); assertThat(row.getCreatedBy()).isEqualTo(actor);
        var history=db.queryForMap("SELECT * FROM cms_article_status_history WHERE article_id=?",result.id());
        assertThat(history.get("action")).isEqualTo("CREATE"); assertThat(history.get("from_status")).isNull();
        assertThat(history.get("to_status")).isEqualTo("DRAFT"); assertThat(history.get("operator_id")).isEqualTo(actor);
    }
    @Test void editsOnlyContentPreservesIdentityAndStatusAndClearsOptionalSummary() {
        var article=create(); var other=categories.create(new CategoryRequests.Create(null,"另一分类",0));
        var updated=service.update(article.id(),new Update("新标题",null,"<p>新正文</p>",other.id(),0));
        assertThat(updated.version()).isOne(); assertThat(updated.summary()).isNull(); assertThat(updated.categoryId()).isEqualTo(other.id());
        assertThat(updated.authorId()).isEqualTo(actor); assertThat(updated.status()).isEqualTo("DRAFT");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM cms_article_status_history",Integer.class)).isOne();
        error(409,()->service.update(article.id(),new Update("旧请求",null,"正文",category,0)));
        error(409,()->service.delete(article.id(),0));
        assertThat(service.detail(article.id()).title()).isEqualTo("新标题");
    }
    @Test void enforcesEditingAndDeletionStateSets() {
        for (String state : List.of("DRAFT","REJECTED","OFFLINE","PENDING","APPROVED","PUBLISHED")) {
            var article=create(); db.update("UPDATE cms_article SET status=?,review_comment='原因' WHERE id=?",state,article.id());
            if (List.of("DRAFT","REJECTED").contains(state)) {
                var result=service.update(article.id(),new Update("已编辑",null,"正文",category,0));
                assertThat(result.status()).isEqualTo(state); service.delete(article.id(),1);
            } else {
                error(409,()->service.update(article.id(),new Update("非法编辑",null,"正文",category,0)));
                if (state.equals("OFFLINE")) service.delete(article.id(),0);
                else error(409,()->service.delete(article.id(),0));
            }
        }
    }
    @Test void deletedArticlesDisappearWhileHistoryAndUserReferencesRemain() {
        var article=create(); service.delete(article.id(),0);
        assertThat(mapper.selectById(article.id())).isNull(); error(404,()->service.detail(article.id()));
        error(404,()->service.delete(article.id(),0));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM cms_article_status_history WHERE article_id=?",Integer.class,article.id())).isOne();
        assertThat(db.queryForObject("SELECT version FROM cms_article WHERE id=?",Integer.class,article.id())).isOne();
    }
    @Test void disabledCategoryBlocksSavingButAllowsDeletingHistoricalArticle() {
        var article=create(); categories.changeStatus(category,new CategoryRequests.ChangeStatus("DISABLED",0));
        error(409,()->create()); error(409,()->service.update(article.id(),new Update("不能保存",null,"正文",category,0)));
        assertThat(service.detail(article.id()).categoryName()).isEqualTo("文章分类"); service.delete(article.id(),0);
    }
    @Test void validatesRequestsRejectsEmptySanitizedBodyAndUnknownCategory() {
        assertThatThrownBy(()->service.create(new Create("　",null,"正文",category))).isInstanceOf(ConstraintViolationException.class);
        error(400,()->service.create(new Create("标题",null,"<script>bad()</script>",category)));
        error(404,()->service.create(new Create("标题",null,"正文",Long.MAX_VALUE)));
        RequestContextHolder.resetRequestAttributes(); error(401,()->create());
    }
    @Test void historyFailureRollsBackArticleInsertion() {
        db.execute("ALTER TABLE cms_article_status_history ADD CONSTRAINT inject_history_failure CHECK(action<>'CREATE')");
        try { assertThatThrownBy(()->create()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class); }
        finally { db.execute("ALTER TABLE cms_article_status_history DROP CONSTRAINT inject_history_failure"); }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM cms_article",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM cms_article_status_history",Integer.class)).isZero();
    }
    @Test void outerRollbackRestoresEditedBodyAndVersion() {
        var article=create(); var tx=new TransactionTemplate(transactions);
        assertThatThrownBy(()->tx.execute(status->{service.update(article.id(),new Update("回滚",null,"新正文",category,0));
            throw new IllegalStateException("failure");})).isInstanceOf(IllegalStateException.class);
        assertThat(service.detail(article.id()).version()).isZero(); assertThat(service.detail(article.id()).title()).isEqualTo("原题");
    }
    @Test void detailsKeepDisabledAuthorAndHideDeletedAuthorWithFallback() {
        var article=create();
        db.update("INSERT INTO sys_user(username,password_hash,display_name) VALUES ('reader','unused','读取者')");
        db.update("UPDATE sys_user SET status='DISABLED' WHERE id=?",actor);
        actor=db.queryForObject("SELECT id FROM sys_user WHERE username='reader'",Long.class); authenticate();
        assertThat(service.detail(article.id()).authorDisplayName()).isEqualTo("文章作者");
        db.update("UPDATE sys_user SET deleted=1 WHERE id=?",article.authorId());
        assertThat(service.detail(article.id()).authorDisplayName()).isEqualTo("未知用户");
    }
    @Test void concurrentSameVersionSavesHaveOneWinner() throws Exception {
        var article=create(); var start=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
        Callable<Integer> attempt=()->{ start.await(); authenticate(); try {
            service.update(article.id(),new Update(Thread.currentThread().getName(),null,"正文",category,0)); return 200;
        } catch(BusinessException e) {return e.getHttpStatus().value();} finally {RequestContextHolder.resetRequestAttributes();}};
        try {var first=pool.submit(attempt); var second=pool.submit(attempt); start.countDown();
            assertThat(List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        } finally {pool.shutdownNow();}
        assertThat(service.detail(article.id()).version()).isOne();
    }
    private ArticleDetailVO create() {return service.create(new Create("原题","摘要","<p>原正文</p>",category));}
    private void authenticate() {var request=new MockHttpServletRequest(); request.getSession().setAttribute(SessionKeys.CURRENT_USER_ID,actor);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request)); ContentTestIdentity.authenticate(authentication);}
    private void error(int code,org.assertj.core.api.ThrowableAssert.ThrowingCallable work) {
        assertThatThrownBy(work).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getHttpStatus().value()).isEqualTo(code));
    }
}
