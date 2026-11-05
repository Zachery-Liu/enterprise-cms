package com.zachery.cms.config;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zachery.cms.modules.identity.persistence.mapper.UserMapper;
import com.zachery.cms.modules.identity.auth.service.LoginService;
import com.zachery.cms.modules.content.persistence.mapper.ContentScanProbeMapper;
import com.zachery.cms.security.annotation.AnonymousAccess;
import com.zachery.cms.support.*;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.annotation.*;
import org.junit.jupiter.api.*;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.WebApplicationContext;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestPropertySource(properties = "cms.db.url=jdbc:h2:mem:ssm_wiring;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ContextHierarchy({
    @ContextConfiguration(name="root", classes={RootConfiguration.class, TestDatabaseConfiguration.class}),
    @ContextConfiguration(name="web", classes={WebConfiguration.class, MockMvcConfiguration.class, SsmWiringIntegrationTest.Probes.class})
})
class SsmWiringIntegrationTest extends SpringWebIntegrationTest {
    @Autowired WebApplicationContext web;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserMapper users;
    @Autowired ContentScanProbeMapper content;
    @BeforeEach void seed() {
        jdbc.update("DELETE FROM sys_user");
        for (int i=0; i<3; i++) jdbc.update("INSERT INTO sys_user(username,password_hash,display_name) VALUES (?,'test-only',?)", "scan_"+i, "Scan "+i);
    }
    @Test void businessBeansLiveOnlyInRootAndControllersOnlyInChild() {
        assertThat(web.getBean(LoginService.class)).isSameAs(web.getParent().getBean(LoginService.class));
        assertThat(web.containsLocalBean("loginService")).isFalse();
        assertThat(web.getParent().getBeansOfType(ProbeController.class)).isEmpty();
        assertThat(web.getBean(ProbeController.class)).isNotNull();
    }
    @Test void mvcChildActuallyAppliesMethodAspect() throws Exception {
        assertThat(AopUtils.isAopProxy(web.getBean(ProbeController.class))).isTrue();
        mvc.perform(get("/api/ssm-wiring/proxy")).andExpect(status().isOk()).andExpect(content().string("true"));
    }
    @Test void contentMapperAndXmlAreLoadedAndDatabasePaginationWorks() {
        assertThat(content.countUsers()).isEqualTo(3);
        var page = users.selectPage(new Page<>(2, 2), new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.zachery.cms.modules.identity.persistence.entity.UserEntity>().orderByAsc("id"));
        assertThat(page.getTotal()).isEqualTo(3);
        assertThat(page.getRecords()).hasSize(1);
        assertThat(page.getRecords().get(0).getUsername()).isEqualTo("scan_2");
    }
    @Configuration(proxyBeanMethods=false)
    @Import({ProbeController.class, ProbeAspect.class})
    static class Probes {}
    @RestController
    public static class ProbeController {
        @GetMapping("/api/ssm-wiring/proxy") @AnonymousAccess
        public boolean probe(HttpServletRequest request) { return Boolean.TRUE.equals(request.getAttribute("aspectVisited")); }
    }
    @Aspect
    public static class ProbeAspect {
        @Before("execution(* com.zachery.cms.config.SsmWiringIntegrationTest.ProbeController.probe(..)) && args(request)")
        public void visit(HttpServletRequest request) { request.setAttribute("aspectVisited", true); }
    }
}
