package com.zachery.cms.modules.content.publication;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zachery.cms.common.exception.BusinessException;
import com.zachery.cms.config.RootConfiguration;
import com.zachery.cms.modules.content.article.dto.ArticleRequests;
import com.zachery.cms.modules.content.article.service.ArticleService;
import com.zachery.cms.modules.content.category.dto.CategoryRequests;
import com.zachery.cms.modules.content.category.service.CategoryService;
import com.zachery.cms.security.session.SessionKeys;
import com.zachery.cms.security.session.SessionAuthenticationInterceptor;
import com.zachery.cms.support.ContentTestIdentity;
import com.zachery.cms.support.ContentTestConfiguration;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.context.request.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@org.springframework.test.context.TestPropertySource(locations="classpath:cms-test.properties", properties="cms.db.url=jdbc:h2:mem:a_content;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@SpringJUnitConfig({RootConfiguration.class,ContentTestConfiguration.class}) @ActiveProfiles("test")
class ArticleLifecycleIntegrationTest {
    @Autowired SessionAuthenticationInterceptor authentication;
    @Autowired ArticleService articles;
    @Autowired ArticleLifecycleService lifecycle;
    @Autowired PublicationQueryService queries;
    @Autowired CategoryService categories;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    long actor,reviewer,category;
    @BeforeEach void seed() {
        RequestContextHolder.resetRequestAttributes();
        db.update("DELETE FROM cms_article_status_history");db.update("DELETE FROM cms_article");
        db.update("UPDATE cms_category SET deleted=1,parent_id=NULL");db.update("DELETE FROM cms_category");db.update("DELETE FROM sys_user");
        db.update("INSERT INTO sys_user(username,password_hash,display_name) VALUES ('life-editor','unused','编辑'),('life-reviewer','unused','审核人')");
        actor=db.queryForObject("SELECT id FROM sys_user WHERE username='life-editor'",Long.class);
        reviewer=db.queryForObject("SELECT id FROM sys_user WHERE username='life-reviewer'",Long.class);
        authenticate(actor);category=categories.create(new CategoryRequests.Create(null,"生命周期分类",0)).id();
    }
    @AfterEach void clear() {RequestContextHolder.resetRequestAttributes();}
    @Test void completeRejectResubmitPublishOfflineReopenFlowPersistsExactHistoryAndMetadata() {
        long id=create();act(id,ArticleAction.SUBMIT,0,null);authenticate(reviewer);
        act(id,ArticleAction.REJECT,1,"  请补充依据  ");
        assertThat(articles.detail(id).reviewerId()).isEqualTo(reviewer);
        assertThat(articles.detail(id).reviewComment()).isEqualTo("请补充依据");
        authenticate(actor);act(id,ArticleAction.SUBMIT,2,null);
        assertThat(articles.detail(id).reviewComment()).isNull();assertThat(articles.detail(id).reviewerId()).isNull();
        authenticate(reviewer);act(id,ArticleAction.APPROVE,3,"通过");act(id,ArticleAction.PUBLISH,4,null);
        var published=articles.detail(id).publishedAt();assertThat(published).isNotNull();
        act(id,ArticleAction.OFFLINE,5,null);assertThat(articles.detail(id).publishedAt()).isEqualTo(published);
        act(id,ArticleAction.REOPEN,6,null);
        var reopened=articles.detail(id);assertThat(reopened.status()).isEqualTo("DRAFT");assertThat(reopened.version()).isEqualTo(7);
        assertThat(reopened.publishedAt()).isNull();assertThat(reopened.reviewedAt()).isNull();
        assertThat(queries.history(id,1,100).records()).extracting(HistoryVO::action)
                .containsExactly("CREATE","SUBMIT","REJECT","SUBMIT","APPROVE","PUBLISH","OFFLINE","REOPEN");
        assertThat(queries.history(id,1,100).records().get(2).operatorId()).isEqualTo(reviewer);
    }
    @Test void refusesIllegalRepeatAndStaleTransitionsWithoutExtraHistory() {
        long id=create();error(409,()->act(id,ArticleAction.PUBLISH,0,null));
        act(id,ArticleAction.SUBMIT,0,null);
        error(409,()->act(id,ArticleAction.SUBMIT,1,null));error(409,()->act(id,ArticleAction.APPROVE,0,null));
        error(400,()->act(id,ArticleAction.REJECT,1,"　 "));
        assertThat(queries.history(id,1,20).total()).isEqualTo(2);
        assertThat(articles.detail(id).status()).isEqualTo("PENDING");
    }
    @Test void categoryDisabledBlocksSubmissionAndPublicationButAllowsOfflineAndReopen() {
        long id=create();categories.changeStatus(category,new CategoryRequests.ChangeStatus("DISABLED",0));
        error(409,()->act(id,ArticleAction.SUBMIT,0,null));
        categories.changeStatus(category,new CategoryRequests.ChangeStatus("ENABLED",1));
        act(id,ArticleAction.SUBMIT,0,null);act(id,ArticleAction.APPROVE,1,null);
        categories.changeStatus(category,new CategoryRequests.ChangeStatus("DISABLED",2));
        error(409,()->act(id,ArticleAction.PUBLISH,2,null));
        categories.changeStatus(category,new CategoryRequests.ChangeStatus("ENABLED",3));act(id,ArticleAction.PUBLISH,2,null);
        categories.changeStatus(category,new CategoryRequests.ChangeStatus("DISABLED",4));
        assertThat(queries.publicDetail(id).id()).isEqualTo(id);
        act(id,ArticleAction.OFFLINE,3,null);act(id,ArticleAction.REOPEN,4,null);
        assertThat(articles.detail(id).status()).isEqualTo("DRAFT");
    }
    @Test void failedHistoryInsertRollsBackStateVersionAndReviewer() {
        long id=create();act(id,ArticleAction.SUBMIT,0,null);
        db.execute("ALTER TABLE cms_article_status_history ADD CONSTRAINT inject_approval_failure CHECK(action<>'APPROVE')");
        try {assertThatThrownBy(()->act(id,ArticleAction.APPROVE,1,"通过")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);}
        finally {db.execute("ALTER TABLE cms_article_status_history DROP CONSTRAINT inject_approval_failure");}
        assertThat(articles.detail(id).status()).isEqualTo("PENDING");assertThat(articles.detail(id).version()).isOne();
        assertThat(articles.detail(id).reviewerId()).isNull();assertThat(queries.history(id,1,100).total()).isEqualTo(2);
    }
    @Test void anonymousOnlyReadsPublishedUndeletedContentWithSafeProjectionAndImmediateOfflineVisibility() throws Exception {
        long draft=create(),published=create(),deleted=create();publish(published);publish(deleted);
        db.update("UPDATE cms_article SET deleted=1 WHERE id=?",deleted);
        RequestContextHolder.resetRequestAttributes();
        var page=queries.publicPage(new PublicationPageQuery(1,20,"标题",category));
        assertThat(page.total()).isOne();assertThat(page.records().get(0).id()).isEqualTo(published);
        String encoded=json.writeValueAsString(queries.publicDetail(published));
        assertThat(encoded).doesNotContain("reviewer","reviewComment","authorId","version","password","operatorId");
        error(404,()->queries.publicDetail(draft));error(404,()->queries.publicDetail(deleted));
        error(401,()->queries.history(published,1,20));
        authenticate(actor);act(published,ArticleAction.OFFLINE,3,null);
        RequestContextHolder.resetRequestAttributes();error(404,()->queries.publicDetail(published));
        assertThat(queries.publicPage(new PublicationPageQuery(null,null,null,null)).total()).isZero();
    }
    @Test void reviewQueueUsesLatestSubmissionTimeAndDoesNotDuplicateResubmittedArticle() {
        long id=create();act(id,ArticleAction.SUBMIT,0,null);act(id,ArticleAction.REJECT,1,"重提");act(id,ArticleAction.SUBMIT,2,null);
        var history=queries.history(id,1,100).records();
        long first=history.get(1).id(),last=history.get(3).id();
        db.update("UPDATE cms_article_status_history SET created_at='2026-10-28 00:00:00' WHERE id=?",first);
        db.update("UPDATE cms_article_status_history SET created_at='2026-10-29 00:00:00' WHERE id=?",last);
        var queue=queries.reviewQueue(new ReviewQueueQuery(1,1,"标题",category,actor,
                OffsetDateTime.parse("2026-10-29T08:00:00+08:00"),OffsetDateTime.parse("2026-10-30T00:00:00Z")));
        assertThat(queue.total()).isOne();assertThat(queue.records()).hasSize(1);
        assertThat(queue.records().get(0).submittedAt()).isEqualTo(Instant.parse("2026-10-29T00:00:00Z"));
        assertThat(queries.history(id,1,2).total()).isEqualTo(4);assertThat(queries.history(id,2,2).records()).hasSize(2);
        act(id,ArticleAction.APPROVE,3,null);
        assertThat(queries.reviewQueue(new ReviewQueueQuery(null,null,null,null,null,null,null)).total()).isZero();
    }
    @Test void deletedMissingAndAnonymousTransitionsAreRejected() {
        long id=create();articles.delete(id,0);error(404,()->act(id,ArticleAction.SUBMIT,1,null));
        error(404,()->act(Long.MAX_VALUE,ArticleAction.SUBMIT,0,null));
        RequestContextHolder.resetRequestAttributes();error(401,()->act(id,ArticleAction.SUBMIT,1,null));
    }
    @Test void concurrentApprovalAndRejectionHaveOneWinnerAndOneHistory() throws Exception {
        long id=create();act(id,ArticleAction.SUBMIT,0,null);
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {var first=pool.submit(()->attempt(start,id,ArticleAction.APPROVE));var second=pool.submit(()->attempt(start,id,ArticleAction.REJECT));
            start.countDown();assertThat(List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        } finally {pool.shutdownNow();}
        assertThat(queries.history(id,1,100).total()).isEqualTo(3);assertThat(articles.detail(id).version()).isEqualTo(2);
    }
    private int attempt(CountDownLatch start,long id,ArticleAction action) throws Exception {
        start.await();authenticate(reviewer);try {act(id,action,1,"处理意见");return 200;}
        catch(BusinessException error){return error.getHttpStatus().value();}finally {RequestContextHolder.resetRequestAttributes();}
    }
    private long create(){return articles.create(new ArticleRequests.Create("标题","摘要","<p>正文</p>",category)).id();}
    private void publish(long id){act(id,ArticleAction.SUBMIT,0,null);act(id,ArticleAction.APPROVE,1,null);act(id,ArticleAction.PUBLISH,2,null);}
    private TransitionResult act(long id,ArticleAction action,int version,String comment){return lifecycle.transition(id,action,new TransitionCommand(version,comment));}
    private void authenticate(long id){var request=new MockHttpServletRequest();request.getSession().setAttribute(SessionKeys.CURRENT_USER_ID,id);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request)); ContentTestIdentity.authenticate(authentication);}
    private void error(int status,org.assertj.core.api.ThrowableAssert.ThrowingCallable work){assertThatThrownBy(work)
            .isInstanceOfSatisfying(BusinessException.class,error->assertThat(error.getHttpStatus().value()).isEqualTo(status));}
}
