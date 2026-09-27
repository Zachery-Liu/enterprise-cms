package com.zachery.cms.modules.content.article;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zachery.cms.common.exception.BusinessException;
import com.zachery.cms.config.RootConfiguration;
import com.zachery.cms.modules.content.article.dto.ArticlePageQuery;
import com.zachery.cms.modules.content.article.service.ArticleQueryService;
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
import org.springframework.web.context.request.*;
import java.time.OffsetDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@org.springframework.test.context.TestPropertySource(locations="classpath:cms-test.properties", properties="cms.db.url=jdbc:h2:mem:a_content;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@SpringJUnitConfig({RootConfiguration.class,ContentTestConfiguration.class}) @ActiveProfiles("test")
class ArticlePageIntegrationTest {
    @Autowired SessionAuthenticationInterceptor authentication;
    @Autowired ArticleQueryService service;
    @Autowired JdbcTemplate db;
    @Autowired CategorySelectCounter sql;
    @Autowired ObjectMapper json;
    long actor,author,category,otherCategory;
    @BeforeEach void seed() {
        RequestContextHolder.resetRequestAttributes();
        db.update("DELETE FROM cms_article_status_history");db.update("DELETE FROM cms_article");
        db.update("UPDATE cms_category SET deleted=1,parent_id=NULL");db.update("DELETE FROM cms_category");db.update("DELETE FROM sys_user");
        db.update("INSERT INTO sys_user(username,password_hash,display_name) VALUES ('page-reader','unused','读取者'),('page-author','unused','作者')");
        actor=db.queryForObject("SELECT id FROM sys_user WHERE username='page-reader'",Long.class);
        author=db.queryForObject("SELECT id FROM sys_user WHERE username='page-author'",Long.class);
        db.update("INSERT INTO cms_category(name,created_by,updated_by) VALUES ('新闻',?,?),('公告',?,?)",actor,actor,actor,actor);
        category=db.queryForObject("SELECT id FROM cms_category WHERE name='新闻'",Long.class);
        otherCategory=db.queryForObject("SELECT id FROM cms_category WHERE name='公告'",Long.class);
        var request=new MockHttpServletRequest();request.getSession().setAttribute(SessionKeys.CURRENT_USER_ID,actor);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request)); ContentTestIdentity.authenticate(authentication);
        sql.reset();
    }
    @AfterEach void clear() {RequestContextHolder.resetRequestAttributes();}
    @Test void defaultPaginationUsesDatabaseLimitAndStableIdTieBreakWithNoBodyOrDeletedRows() throws Exception {
        for(int index=0;index<25;index++) insert("标题"+index,category,author,"DRAFT",0);
        insert("已删除",category,author,"DRAFT",1);
        sql.reset();
        var page=service.page(query(null,null,null,null,null,null,null,null,null,null,null,null));
        assertThat(page.total()).isEqualTo(25);assertThat(page.page()).isOne();assertThat(page.size()).isEqualTo(20);
        assertThat(page.records()).hasSize(20);
        assertThat(page.records()).extracting(row->row.id()).isSortedAccordingTo(Comparator.reverseOrder());
        String encoded=json.writeValueAsString(page);assertThat(encoded).doesNotContain("正文不可进入列表","\"content\"","password");
        var selects=sql.statements().stream().filter(statement->statement.contains(" from cms_article ")).toList();
        assertThat(selects).hasSize(2);assertThat(selects.stream().filter(statement->!statement.contains("count(")))
                .singleElement().satisfies(statement->assertThat(statement).contains("limit").doesNotContain("a.content"));
        var second=service.page(query(2,20,null,null,null,null,null,null,null,null,null,null));
        assertThat(second.records()).hasSize(5);assertThat(second.total()).isEqualTo(25);
        assertThat(second.records()).noneMatch(row->page.records().stream().anyMatch(first->first.id().equals(row.id())));
    }
    @Test void combinesAllFiltersAndNormalizesOffsetDateRangesWithExclusiveEnd() {
        insert("企业 新闻",category,author,"APPROVED",0);
        insert("企业 新闻",otherCategory,author,"APPROVED",0);
        insert("企业 新闻",category,actor,"APPROVED",0);
        insert("企业 新闻",category,author,"DRAFT",0);
        insert("不匹配",category,author,"APPROVED",0);
        var page=service.page(query(1,10,"新闻",category,"APPROVED",author,
                time("2026-10-29T08:00:00+08:00"),time("2026-10-29T08:00:01+08:00"),
                time("2026-10-29T00:00:00Z"),time("2026-10-29T00:00:01Z"),"createdAt","ASC"));
        assertThat(page.total()).isOne();assertThat(page.records()).singleElement()
                .satisfies(row->{assertThat(row.categoryName()).isEqualTo("新闻");assertThat(row.authorDisplayName()).isEqualTo("作者");});
        assertThat(service.page(query(1,10,null,null,null,null,null,time("2026-10-29T00:00:00Z"),null,null,null,null)).total()).isZero();
    }
    @Test void titleWildcardsAreLiteralAndSortColumnsAreWhitelisted() {
        insert("100%_完成!",category,author,"DRAFT",0);insert("100ab完成",category,author,"DRAFT",0);
        assertThat(service.page(query(1,20,"%_",null,null,null,null,null,null,null,"title","ASC")).total()).isOne();
        assertThat(service.page(query(1,20,"!",null,null,null,null,null,null,null,null,null)).total()).isOne();
        for(String sort:List.of("createdAt","updatedAt","publishedAt","title"))
            assertThat(service.page(query(1,20,null,null,null,null,null,null,null,null,sort,"ASC")).records()).hasSize(2);
        assertThatThrownBy(()->service.page(query(1,20,null,null,null,null,null,null,null,null,"title DESC; DROP TABLE cms_article","ASC")))
                .isInstanceOf(ConstraintViolationException.class);
    }
    @Test void rejectsInvalidPageStatusDirectionAndRangesAndMissingOffset() {
        for(ArticlePageQuery request:List.of(query(0,20,null,null,null,null,null,null,null,null,null,null),
                query(1,101,null,null,null,null,null,null,null,null,null,null),query(1,0,null,null,null,null,null,null,null,null,null,null),
                query(1,20,null,-1L,null,null,null,null,null,null,null,null),query(1,20,null,null,"BROKEN",null,null,null,null,null,null,null),
                query(1,20,null,null,null,null,null,null,null,null,null,"desc"))) {
            assertThatThrownBy(()->service.page(request)).isInstanceOf(ConstraintViolationException.class);
        }
        assertThatThrownBy(()->service.page(query(1,20,null,null,null,null,time("2026-10-30T00:00:00Z"),time("2026-10-29T00:00:00Z"),null,null,null,null)))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getHttpStatus().value()).isEqualTo(400));
        assertThatThrownBy(()->json.readValue("{\"createdFrom\":\"2026-10-29T00:00:00\"}",ArticlePageQuery.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.InvalidFormatException.class);
    }
    @Test void keepsDisabledAuthorFallsBackForDeletedAuthorAndRetainsCategoryWithoutExtraLookups() {
        insert("文章",category,author,"DRAFT",0);db.update("UPDATE sys_user SET status='DISABLED' WHERE id=?",author);
        db.update("UPDATE cms_category SET status='DISABLED' WHERE id=?",category);
        var page=service.page(query(1,20,null,null,null,null,null,null,null,null,null,null));
        assertThat(page.records().get(0).authorDisplayName()).isEqualTo("作者");assertThat(page.records().get(0).categoryName()).isEqualTo("新闻");
        db.update("UPDATE sys_user SET deleted=1 WHERE id=?",author);
        assertThat(service.page(query(1,20,null,null,null,null,null,null,null,null,null,null)).records().get(0).authorDisplayName()).isEqualTo("未知用户");
    }
    @Test void authorLookupIsOneBatchForOneHundredRowsAndSkippedOnEmptyOrOutOfRangePages() {
        for(int index=0;index<100;index++) insert("文章"+index,category,index%2==0?author:actor,"DRAFT",0);
        sql.reset();var page=service.page(query(1,100,null,null,null,null,null,null,null,null,null,null));
        assertThat(page.records()).hasSize(100);
        assertThat(authorQueries()).hasSize(1);
        assertThat(authorQueries().get(0)).contains(" in (");
        assertThat(sql.count()).isZero(); // Categories are joined into the article query.
        sql.reset();var missing=service.page(query(99,100,null,null,null,null,null,null,null,null,null,null));
        assertThat(missing.records()).isEmpty();assertThat(missing.total()).isEqualTo(100);assertThat(authorQueries()).isEmpty();
        sql.reset();assertThat(service.page(query(1,20,"不存在",null,null,null,null,null,null,null,null,null)).total()).isZero();
        assertThat(authorQueries()).isEmpty();
    }
    @Test void anonymousCannotReadBackendPages() {
        RequestContextHolder.resetRequestAttributes();
        assertThatThrownBy(()->service.page(query(null,null,null,null,null,null,null,null,null,null,null,null)))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getHttpStatus().value()).isEqualTo(401));
    }
    private List<String> authorQueries() {return sql.statements().stream().filter(s->s.contains(" from sys_user ")&&s.contains(" in (")&&!s.contains("join sys_user_role")).toList();}
    private void insert(String title,long categoryId,long authorId,String state,int deleted) {
        db.update("INSERT INTO cms_article(title,content,category_id,author_id,status,deleted,created_by,updated_by,created_at,updated_at) "
                +"VALUES (?,'正文不可进入列表',?,?,?,?,?,?,'2026-10-29 00:00:00','2026-10-29 00:00:00')",title,categoryId,authorId,state,deleted,actor,actor);
    }
    private OffsetDateTime time(String value) {return OffsetDateTime.parse(value);}
    private ArticlePageQuery query(Integer page,Integer size,String title,Long categoryId,String state,Long authorId,
            OffsetDateTime createdFrom,OffsetDateTime createdTo,OffsetDateTime updatedFrom,OffsetDateTime updatedTo,String sort,String direction) {
        return new ArticlePageQuery(page,size,title,categoryId,state,authorId,createdFrom,createdTo,updatedFrom,updatedTo,sort,direction);
    }
}
