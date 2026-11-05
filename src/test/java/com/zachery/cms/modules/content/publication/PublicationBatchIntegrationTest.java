package com.zachery.cms.modules.content.publication;

import com.zachery.cms.common.exception.BusinessException;
import com.zachery.cms.config.RootConfiguration;
import com.zachery.cms.modules.content.article.dto.ArticleRequests;
import com.zachery.cms.modules.content.article.service.ArticleService;
import com.zachery.cms.modules.content.category.dto.CategoryRequests;
import com.zachery.cms.modules.content.category.service.CategoryService;
import com.zachery.cms.security.session.SessionKeys;
import com.zachery.cms.security.session.SessionAuthenticationInterceptor;
import com.zachery.cms.support.ContentTestIdentity;
import com.zachery.cms.support.*;
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
class PublicationBatchIntegrationTest {
    @Autowired SessionAuthenticationInterceptor authentication;
    @Autowired PublicationBatchService batches;
    @Autowired ArticleLifecycleService lifecycle;
    @Autowired ArticleService articles;
    @Autowired CategoryService categories;
    @Autowired JdbcTemplate db;
    @Autowired CategorySelectCounter sql;
    @Autowired PlatformTransactionManager transactions;
    long actor,category;
    @BeforeEach void seed() {
        RequestContextHolder.resetRequestAttributes();
        db.update("DELETE FROM cms_article_status_history");db.update("DELETE FROM cms_article");
        db.update("UPDATE cms_category SET deleted=1,parent_id=NULL");db.update("DELETE FROM cms_category");db.update("DELETE FROM sys_user");
        db.update("INSERT INTO sys_user(username,password_hash,display_name) VALUES ('batch-actor','unused','批量发布人')");
        actor=db.queryForObject("SELECT id FROM sys_user WHERE username='batch-actor'",Long.class);
        authenticate();category=categories.create(new CategoryRequests.Create(null,"批量分类",0)).id();
    }
    @AfterEach void clear(){RequestContextHolder.resetRequestAttributes();}
    @Test void publishesOneAndFiftyWithOneArticlePreflightAndRequestOrderResults() {
        long one=approved();assertThat(batches.publish(command(one)).count()).isOne();
        List<Long> ids=new ArrayList<>();for(int index=0;index<50;index++)ids.add(approved());
        Collections.reverse(ids);sql.reset();var result=batches.publish(command(ids.toArray(Long[]::new)));
        assertThat(result.count()).isEqualTo(50);assertThat(result.items()).extracting(TransitionResult::id).containsExactlyElementsOf(ids);
        assertThat(result.items()).allSatisfy(row->{assertThat(row.status()).isEqualTo("PUBLISHED");assertThat(row.version()).isEqualTo(3);});
        assertThat(sql.statements().stream().filter(statement->statement.contains(" from cms_article "))).hasSize(1);
        assertThat(sql.count()).isLessThanOrEqualTo(4); // Constant bound; already-held snapshot may use session cache.
        assertThat(result.items()).extracting(TransitionResult::updatedAt).containsOnly(result.items().get(0).updatedAt());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM cms_article_status_history WHERE action='PUBLISH'",Integer.class)).isEqualTo(51);
    }
    @Test void validatesNullEmptyOversizedAndInvalidItemsBeforeWriting() {
        List<BatchPublishCommand> invalid=new ArrayList<>();invalid.add(null);invalid.add(new BatchPublishCommand(null));invalid.add(new BatchPublishCommand(List.of()));
        invalid.add(new BatchPublishCommand(Collections.nCopies(51,new BatchPublishCommand.Item(1L,2))));
        invalid.add(new BatchPublishCommand(Arrays.asList((BatchPublishCommand.Item)null)));
        invalid.add(new BatchPublishCommand(List.of(new BatchPublishCommand.Item(-1L,2))));
        invalid.add(new BatchPublishCommand(List.of(new BatchPublishCommand.Item(1L,-1))));
        for(var request:invalid)assertThatThrownBy(()->batches.publish(request)).isInstanceOf(ConstraintViolationException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM cms_article_status_history",Integer.class)).isZero();
    }
    @Test void duplicateIdsAreRejectedInsteadOfSilentlyDeduplicated() {
        long id=approved();assertThatThrownBy(()->batches.publish(command(id,id))).isInstanceOfSatisfying(BatchPublicationException.class,
                error->{assertThat(error.getHttpStatus().value()).isEqualTo(400);assertThat(error.getFailures().get(0).id()).isEqualTo(id);});
        assertApproved(id);
    }
    @Test void preflightReportsInvalidStateStaleMissingAndDeletedItemsWithoutPartialPublish() {
        long good=approved(),stale=approved(),draft=create(),deleted=create();articles.delete(deleted,0);
        var request=new BatchPublishCommand(List.of(new BatchPublishCommand.Item(good,2),new BatchPublishCommand.Item(stale,1),
                new BatchPublishCommand.Item(draft,0),new BatchPublishCommand.Item(deleted,1),new BatchPublishCommand.Item(Long.MAX_VALUE,0)));
        assertThatThrownBy(()->batches.publish(request)).isInstanceOfSatisfying(BatchPublicationException.class,error->{
            assertThat(error.getFailures()).extracting(BatchPublicationException.Failure::id).containsExactly(stale,draft,deleted,Long.MAX_VALUE);
            assertThat(error.getMessage()).doesNotContain("SELECT","UPDATE","INSERT","jdbc:");
        });
        assertApproved(good);assertApproved(stale);assertThat(publishHistory()).isZero();
    }
    @Test void disabledCategoryAndInvalidBodyRejectEntireBatch() {
        long first=approved(),second=approved();
        db.update("UPDATE cms_article SET content='<script>bad()</script>' WHERE id=?",second);
        assertThatThrownBy(()->batches.publish(command(first,second))).isInstanceOfSatisfying(BatchPublicationException.class,
                error->assertThat(error.getFailures().get(0).id()).isEqualTo(second));
        assertApproved(first);db.update("UPDATE cms_article SET content='正文' WHERE id=?",second);
        categories.changeStatus(category,new CategoryRequests.ChangeStatus("DISABLED",0));
        assertThatThrownBy(()->batches.publish(command(first,second))).isInstanceOf(BatchPublicationException.class);
        assertApproved(first);assertApproved(second);assertThat(publishHistory()).isZero();
    }
    @Test void secondHistoryFailureRollsBackFirstPublishedRowAndBothHistories() {
        long first=approved(),second=approved();
        db.execute("ALTER TABLE cms_article_status_history ADD CONSTRAINT inject_second_publish_failure CHECK(action<>'PUBLISH' OR article_id<>"+second+")");
        try {assertThatThrownBy(()->batches.publish(command(second,first))).isInstanceOfSatisfying(BatchPublicationException.class,error->{
            assertThat(error.getFailures().get(0).id()).isEqualTo(second);assertThat(error.getMessage()).doesNotContain("constraint","SQL","INSERT");
        });} finally {db.execute("ALTER TABLE cms_article_status_history DROP CONSTRAINT inject_second_publish_failure");}
        assertApproved(first);assertApproved(second);assertThat(publishHistory()).isZero();
    }
    @Test void outerFailureRollsBackCompleteBatchAndRetryWorksOnce() {
        long first=approved(),second=approved();var tx=new TransactionTemplate(transactions);
        assertThatThrownBy(()->tx.execute(status->{batches.publish(command(first,second));throw new IllegalStateException("outer failure");}))
                .isInstanceOf(IllegalStateException.class);
        assertApproved(first);assertApproved(second);assertThat(publishHistory()).isZero();
        batches.publish(command(first,second));assertThat(publishHistory()).isEqualTo(2);
        assertThatThrownBy(()->batches.publish(command(first,second))).isInstanceOf(BatchPublicationException.class);
        assertThat(publishHistory()).isEqualTo(2);
    }
    @Test void overlappingConcurrentBatchesHaveOneWinnerAndNoPartiallyPublishedLosingBatch() throws Exception {
        long first=approved(),shared=approved(),last=approved();var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {var one=pool.submit(()->attempt(start,command(shared,first)));var two=pool.submit(()->attempt(start,command(last,shared)));
            start.countDown();assertThat(List.of(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }finally{pool.shutdownNow();}
        assertThat(db.queryForObject("SELECT COUNT(*) FROM cms_article WHERE status='PUBLISHED'",Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM cms_article WHERE status='APPROVED' AND version=2",Integer.class)).isOne();
        assertThat(publishHistory()).isEqualTo(2);
    }
    @Test void anonymousCannotPublish() {
        long id=approved();RequestContextHolder.resetRequestAttributes();
        assertThatThrownBy(()->batches.publish(command(id))).isInstanceOfSatisfying(BusinessException.class,
                error->assertThat(error.getHttpStatus().value()).isEqualTo(401));
        assertThat(publishHistory()).isZero();
    }
    private int attempt(CountDownLatch start,BatchPublishCommand command)throws Exception {
        start.await();authenticate();try{batches.publish(command);return 200;}catch(BusinessException error){return error.getHttpStatus().value();}
        finally{RequestContextHolder.resetRequestAttributes();}
    }
    private long create(){return articles.create(new ArticleRequests.Create("批量标题",null,"正文",category)).id();}
    private long approved(){long id=create();lifecycle.transition(id,ArticleAction.SUBMIT,new TransitionCommand(0,null));
        lifecycle.transition(id,ArticleAction.APPROVE,new TransitionCommand(1,null));return id;}
    private BatchPublishCommand command(Long... ids){return new BatchPublishCommand(Arrays.stream(ids).map(id->new BatchPublishCommand.Item(id,2)).toList());}
    private void assertApproved(long id){var row=articles.detail(id);assertThat(row.status()).isEqualTo("APPROVED");assertThat(row.version()).isEqualTo(2);assertThat(row.publishedAt()).isNull();}
    private int publishHistory(){return db.queryForObject("SELECT COUNT(*) FROM cms_article_status_history WHERE action='PUBLISH'",Integer.class);}
    private void authenticate(){var request=new MockHttpServletRequest();request.getSession().setAttribute(SessionKeys.CURRENT_USER_ID,actor);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request)); ContentTestIdentity.authenticate(authentication);}
}
