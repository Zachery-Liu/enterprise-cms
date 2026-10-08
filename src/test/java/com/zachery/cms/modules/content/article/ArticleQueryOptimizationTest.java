package com.zachery.cms.modules.content.article;

import com.zachery.cms.common.api.PageResponse;
import com.zachery.cms.config.RootConfiguration;
import com.zachery.cms.modules.content.article.dto.*;
import com.zachery.cms.modules.content.article.service.ArticleQueryService;
import com.zachery.cms.modules.content.article.vo.ArticleListVO;
import com.zachery.cms.security.session.SessionKeys;
import com.zachery.cms.security.session.SessionAuthenticationInterceptor;
import com.zachery.cms.support.ContentTestIdentity;
import com.zachery.cms.modules.identity.user.service.UserReader;
import com.zachery.cms.support.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.context.request.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Deliberately inefficient comparison exists only in this test, never in application code. */
@org.springframework.test.context.TestPropertySource(locations="classpath:cms-test.properties", properties="cms.db.url=jdbc:h2:mem:a_content;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@SpringJUnitConfig({RootConfiguration.class,ContentTestConfiguration.class}) @ActiveProfiles("test")
class ArticleQueryOptimizationTest {
    @Autowired SessionAuthenticationInterceptor authentication;
    @Autowired ArticleQueryService optimized;
    @Autowired UserReader users;
    @Autowired JdbcTemplate db;
    @Autowired CategorySelectCounter sql;

    @AfterEach void clear(){RequestContextHolder.resetRequestAttributes();}
    @Test void measuresRealNPlusOneComparisonAndRequiresEquivalentPages() throws Exception {
        db.update("DELETE FROM cms_article_status_history");db.update("DELETE FROM cms_article");
        db.update("UPDATE cms_category SET deleted=1,parent_id=NULL");db.update("DELETE FROM cms_category");db.update("DELETE FROM sys_user");
        db.update("INSERT INTO sys_user(id,username,password_hash,display_name) VALUES (100000,'optimization-reader','unused','读取者')");
        for(int index=0;index<50;index++) db.update("INSERT INTO sys_user(id,username,password_hash,display_name,status,deleted) VALUES (?,?,?,?,?,?)",
                100001L+index,"author"+index,"unused","作者"+index,index%3==0?"DISABLED":"ACTIVE",index%7==0?1:0);
        for(int index=0;index<5;index++) db.update("INSERT INTO cms_category(id,name,created_by,updated_by) VALUES (?,?,100000,100000)",100000L+index,"分类"+index);
        for(int index=0;index<500;index++) db.update("INSERT INTO cms_article(title,summary,content,category_id,author_id,deleted,created_by,updated_by,created_at,updated_at) "
                +"VALUES (?,?,'正文',?,?,?,100000,100000,'2026-10-29 00:00:00','2026-10-29 00:00:00')",
                "标题"+index,"摘要"+index,100000L+index%5,100001L+index%50,index%25==0?1:0);
        var request=new MockHttpServletRequest();request.getSession().setAttribute(SessionKeys.CURRENT_USER_ID,100000L);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request)); ContentTestIdentity.authenticate(authentication);
        List<String> csv=new ArrayList<>(List.of("database,dataset_rows,visible_rows,page_size,page,iteration,baseline_selects,optimized_selects,baseline_nanos,optimized_nanos,equal"));
        for(int size:List.of(10,50,100)) for(int iteration=1;iteration<=3;iteration++) {
            // Vary the page too, so equivalence includes pagination boundaries and ordering.
            int page=iteration;
            sql.reset();long start=System.nanoTime();var before=baseline(page,size);long beforeNanos=System.nanoTime()-start;long beforeCount=dataSelects();
            sql.reset();start=System.nanoTime();var after=optimized.page(new ArticlePageQuery(page,size,null,null,null,null,null,null,null,null,null,null));
            long afterNanos=System.nanoTime()-start;long afterCount=dataSelects();
            assertThat(after).isEqualTo(before);assertThat(after.total()).isEqualTo(480);
            assertThat(beforeCount).isEqualTo(2+2L*size);assertThat(afterCount).isEqualTo(3);
            csv.add("H2-2.3.232,500,480,"+size+","+page+","+iteration+","+beforeCount+","+afterCount+","+beforeNanos+","+afterNanos+",true");
        }
        Path output=Path.of("target/verification/article-query-comparison.csv");Files.createDirectories(output.getParent());Files.write(output,csv);
    }
    private PageResponse<ArticleListVO> baseline(int page,int size) {
        long total=db.queryForObject("SELECT COUNT(*) FROM cms_article WHERE deleted=?",Long.class,0);
        var rows=db.query("SELECT id,title,summary,category_id,author_id,status,version,created_at,updated_at,published_at "
                +"FROM cms_article WHERE deleted=? ORDER BY updated_at DESC,id DESC LIMIT ? OFFSET ?",
                new BeanPropertyRowMapper<>(ArticlePageRow.class),0,size,(page-1)*size);
        var records=new ArrayList<ArticleListVO>();
        for(var row:rows) {
            String category=db.queryForObject("SELECT name FROM cms_category WHERE id=? AND deleted=0",String.class,row.getCategoryId());
            var author=users.findByIds(List.of(row.getAuthorId())).get(row.getAuthorId());
            records.add(new ArticleListVO(row.getId(),row.getTitle(),row.getSummary(),row.getCategoryId(),category,
                    row.getAuthorId(),author==null?"未知用户":author.displayName(),row.getStatus(),row.getVersion(),utc(row.getCreatedAt()),utc(row.getUpdatedAt()),utc(row.getPublishedAt())));
        }
        return new PageResponse<>(records,total,page,size);
    }
    private long dataSelects(){return sql.statements().stream().filter(s->s.contains(" from cms_article ")||s.contains(" from cms_category ")
            ||(s.contains(" from sys_user ")&&s.contains(" in (")&&!s.contains("join sys_user_role"))).count();}
    private Instant utc(LocalDateTime value){return value==null?null:value.toInstant(ZoneOffset.UTC);}
}
