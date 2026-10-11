package com.zachery.cms.modules.identity.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zachery.cms.common.api.PageResponse;
import com.zachery.cms.config.*;
import com.zachery.cms.modules.identity.persistence.entity.UserEntity;
import com.zachery.cms.modules.identity.persistence.mapper.*;
import com.zachery.cms.modules.identity.persistence.projection.UserRoleRow;
import com.zachery.cms.modules.identity.user.dto.*;
import com.zachery.cms.modules.identity.user.service.UserQueryService;
import com.zachery.cms.security.session.SessionKeys;
import com.zachery.cms.support.*;
import javax.sql.DataSource;
import java.lang.reflect.*;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestPropertySource(properties="cms.db.url=jdbc:h2:mem:userroles;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ContextHierarchy({
    @ContextConfiguration(name="root",classes={RootConfiguration.class,TestDatabaseConfiguration.class,UserRolePageIntegrationTest.Fixtures.class}),
    @ContextConfiguration(name="web",classes={WebConfiguration.class,MockMvcConfiguration.class})
})
class UserRolePageIntegrationTest extends SpringWebIntegrationTest {
    @Autowired UserQueryService queries;
    @Autowired UserMapper users;
    @Autowired RbacQueryMapper roles;
    @Autowired JdbcTemplate jdbc;
    @Autowired SqlProbe sql;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactions;
    List<Long> ids;
    long deletedUser, editor, reviewer, deletedRole, adminRole;

    @BeforeEach void seed() {
        sql.clear();
        for(String table:List.of("sys_user_role","sys_role_permission","sys_operation_log")) jdbc.update("DELETE FROM "+table);
        jdbc.update("UPDATE sys_user SET created_by=NULL,updated_by=NULL");
        for(String table:List.of("sys_user","sys_role","sys_permission")) jdbc.update("DELETE FROM "+table);
        ids=new ArrayList<>();
        for(int i=0;i<120;i++) {
            String name=String.format(Locale.ROOT,"bench_%03d",i);
            jdbc.update("INSERT INTO sys_user(username,password_hash,display_name,status,created_at) VALUES (?,'test-hash',?,?,?)",
                    name,name,i%10==0?"DISABLED":"ACTIVE",Timestamp.valueOf(LocalDateTime.of(2026,10,12,0,0).plusSeconds(i/10)));
            ids.add(jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,name));
        }
        jdbc.update("INSERT INTO sys_user(username,password_hash,display_name,deleted) VALUES ('deleted_account','test-hash','Deleted',1)");
        deletedUser=jdbc.queryForObject("SELECT id FROM sys_user WHERE username='deleted_account'",Long.class);
        editor=role("EDITOR","ENABLED",0); reviewer=role("REVIEWER","DISABLED",0);
        deletedRole=role("DELETED_ROLE","ENABLED",1); adminRole=role("ADMIN","ENABLED",0);
        for(int i=0;i<120;i++) {
            if(i%4==1 || i%4==2) link(ids.get(i),editor);
            if(i%4==2) link(ids.get(i),reviewer);
            if(i%4==3) link(ids.get(i),deletedRole);
        }
        link(deletedUser,editor); link(ids.get(119),adminRole);
        jdbc.update("INSERT INTO sys_permission(code,name) VALUES ('cms:user:read','Read users')");
        long read=jdbc.queryForObject("SELECT id FROM sys_permission WHERE code='cms:user:read'",Long.class);
        jdbc.update("INSERT INTO sys_role_permission(role_id,permission_id,created_by) VALUES (?,?,?)",adminRole,read,ids.get(119));
    }
    @AfterEach void clear() { sql.clear(); }

    @Test void fiveRoundsShowConstantSqlAtTenTwentyAndHundredRows() {
        for(int size:List.of(10,20,100)) {
            UserQuery filter=UserQuery.parse(Map.of("size",""+size));
            for(int round=1;round<=5;round++) {
                var before=measure(()->naive(filter));
                var after=measure(()->queries.query(filter));
                assertThat(after.value()).isEqualTo(before.value());
                assertThat(after.value().records()).hasSize(size);
                assertThat(after.value().total()).isEqualTo(120);
                assertThat(before.sql()).hasSize(size+2);
                assertThat(after.sql()).hasSize(3);
                assertThat(after.sql().get(0).toLowerCase(Locale.ROOT)).contains("count(");
                assertThat(after.sql().get(1).toLowerCase(Locale.ROOT)).contains("limit");
                assertThat(after.sql().get(2).toLowerCase(Locale.ROOT)).contains(" in ");
                System.out.printf(Locale.ROOT,"B17_EVIDENCE pageSize=%d round=%d users=120 before=%d after=%d equal=true%n",
                        size,round,before.sql().size(),after.sql().size());
            }
        }
    }
    @Test void multipleRolesNeverInflateTotalsOrDisplaceUsersAcrossPages() {
        List<Long> actual=new ArrayList<>();
        for(int page=1;page<=6;page++) {
            var result=queries.query(UserQuery.parse(Map.of("page",""+page,"size","20")));
            assertThat(result.total()).isEqualTo(120);
            assertThat(result.records()).hasSize(20);
            actual.addAll(result.records().stream().map(UserListView::id).toList());
        }
        var expected=new ArrayList<>(ids); Collections.reverse(expected);
        assertThat(actual).containsExactlyElementsOf(expected);
        UserQuery filter=UserQuery.parse(Map.of("username","bench_0","status","DISABLED","size","3","page","2",
                "from","2026-10-12T08:00:00+08:00","to","2026-10-12T00:00:09Z"));
        assertThat(queries.query(filter)).isEqualTo(naive(filter));
        assertThat(queries.query(filter).total()).isEqualTo(10);
    }
    @Test void managementRolesPreserveDisabledAssignmentsButNotDeletedOnes() {
        var records=queries.query(UserQuery.parse(Map.of("page","2","size","100"))).records();
        Map<Long,UserListView> indexed=new HashMap<>(); records.forEach(row->indexed.put(row.id(),row));
        assertThat(indexed.get(ids.get(0)).roles()).isEmpty();
        assertThat(indexed.get(ids.get(1)).roles()).extracting("code").containsExactly("EDITOR");
        assertThat(indexed.get(ids.get(2)).roles()).extracting("code").containsExactly("EDITOR","REVIEWER");
        assertThat(indexed.get(ids.get(2)).roles().get(1).status()).isEqualTo("DISABLED");
        assertThat(indexed.get(ids.get(3)).roles()).isEmpty();
        assertThat(indexed.get(ids.get(10)).status()).isEqualTo("DISABLED");
        assertThat(indexed.get(ids.get(10)).roles()).hasSize(2);
        assertThat(roles.selectRolesForUsers(List.of(ids.get(10)))).isEmpty(); // Existing effective-role behavior is unchanged.
        assertThatThrownBy(()->indexed.get(ids.get(2)).roles().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(records::clear).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void emptyResultsSkipRoleSqlAndRetainTotalsBeyondLastPage() {
        for(var params:List.of(Map.of("username","no-match"),Map.of("page","7","size","20"))) {
            var result=measure(()->queries.query(UserQuery.parse(params)));
            assertThat(result.value().records()).isEmpty();
            assertThat(result.sql()).hasSize(1);
            assertThat(result.sql().get(0).toLowerCase(Locale.ROOT)).contains("count(");
            assertThat(result.value().total()).isEqualTo(params.containsKey("username")?0:120);
        }
    }
    @Test void emptyNullAndRepeatedMapperIdsCannotBroadenResults() {
        assertThat(roles.selectAssignedRolesForUsers(List.of())).isEmpty();
        assertThat(roles.selectAssignedRolesForUsers(null)).isEmpty();
        assertThat(roles.selectAssignedRolesForUsers(List.of(deletedUser))).isEmpty();
        var rows=roles.selectAssignedRolesForUsers(List.of(ids.get(2),ids.get(2)));
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(UserRoleRow::userId).containsOnly(ids.get(2));
    }
    @Test void httpKeepsExistingFieldsAndPermissionsWhileAddingRoles() throws Exception {
        mvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users").session(session(ids.get(1)))).andExpect(status().isForbidden());
        mvc.perform(get("/api/users").session(session(ids.get(119))).param("username","bench_002"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].username").value("bench_002"))
                .andExpect(jsonPath("$.data.records[0].roles[0].code").value("EDITOR"))
                .andExpect(jsonPath("$.data.records[0].roles[1].status").value("DISABLED"))
                .andExpect(jsonPath("$.data.records[0].passwordHash").doesNotExist());
    }

    /** Test-only reproduction of an N+1 implementation; never shipped in the WAR. */
    private PageResponse<UserListView> naive(UserQuery filter) {
        var tx=new TransactionTemplate(transactions);
        tx.setReadOnly(true); tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx.execute(ignored->{
            var conditions=new LambdaQueryWrapper<UserEntity>()
                    .select(UserEntity::getId,UserEntity::getUsername,UserEntity::getDisplayName,UserEntity::getStatus,
                            UserEntity::getVersion,UserEntity::getCreatedAt,UserEntity::getUpdatedAt)
                    .apply(filter.username()!=null,"username LIKE {0} ESCAPE '!'",filter.usernamePattern())
                    .eq(filter.status()!=null,UserEntity::getStatus,filter.status())
                    .ge(filter.from()!=null,UserEntity::getCreatedAt,filter.from())
                    .le(filter.to()!=null,UserEntity::getCreatedAt,filter.to())
                    .orderByDesc(UserEntity::getCreatedAt,UserEntity::getId);
            var page=users.selectPage(new Page<>(filter.page().page(),filter.page().size()),conditions);
            var records=page.getRecords().stream().map(user->UserListView.from(UserAdminView.from(user),
                    roles.selectAssignedRolesForUsers(List.of(user.getId())).stream().map(UserRoleRow::role).toList())).toList();
            return new PageResponse<>(records,page.getTotal(),filter.page().page(),filter.page().size());
        });
    }
    record Measurement<T>(T value,List<String> sql) {}
    private <T> Measurement<T> measure(Supplier<T> work) {
        sql.begin();
        try { T value=work.get(); return new Measurement<>(value,sql.statements()); }
        finally { sql.clear(); }
    }
    private MockHttpSession session(long id) {
        var session=new MockHttpSession();session.setAttribute(SessionKeys.CURRENT_USER_ID,id);return session;
    }
    private long role(String code,String status,int deleted) {
        jdbc.update("INSERT INTO sys_role(code,name,status,deleted) VALUES (?,?,?,?)",code,code,status,deleted);
        return jdbc.queryForObject("SELECT id FROM sys_role WHERE code=?",Long.class,code);
    }
    private void link(long user,long role) { jdbc.update("INSERT INTO sys_user_role(user_id,role_id,created_by) VALUES (?,?,?)",user,role,ids.get(119)); }
    @Configuration(proxyBeanMethods=false)
    static class Fixtures { @Bean static SqlProbe userRoleSqlProbe() { return new SqlProbe(); } }
    /** Count JDBC preparation directly, including pagination count statements reused across MyBatis configurations. */
    public static class SqlProbe implements BeanPostProcessor {
        private final ThreadLocal<List<String>> statements=new ThreadLocal<>();
        void begin() { statements.set(new ArrayList<>()); }
        List<String> statements() { return List.copyOf(statements.get()); }
        void clear() { statements.remove(); }
        @Override public Object postProcessAfterInitialization(Object bean, String name) {
            if (!(bean instanceof DataSource source)) return bean;
            return new DelegatingDataSource(source) {
                @Override public Connection getConnection() throws SQLException { return observe(super.getConnection()); }
                @Override public Connection getConnection(String user,String password) throws SQLException {
                    return observe(super.getConnection(user,password));
                }
            };
        }
        private Connection observe(Connection connection) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},
                    (proxy,method,args)->{
                        if (method.getName().equals("prepareStatement") && statements.get()!=null)
                            statements.get().add((String)args[0]);
                        try { return method.invoke(connection,args); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
        }
    }
}
