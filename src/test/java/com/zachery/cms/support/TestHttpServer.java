package com.zachery.cms.support;

import com.zachery.cms.common.context.RequestIdFilter;
import com.zachery.cms.config.WebConfiguration;
import jakarta.servlet.*;
import org.apache.catalina.startup.Tomcat;
import org.springframework.context.ApplicationContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.filter.CharacterEncodingFilter;
import org.springframework.web.servlet.DispatcherServlet;
import java.nio.file.*;
import java.util.*;

/** Real HTTP regression fixture; production is a WAR for external Tomcat. */
public final class TestHttpServer implements AutoCloseable {
    private final Tomcat tomcat = new Tomcat();
    public TestHttpServer(ApplicationContext parent, Class<?> probes) throws Exception {
        Path base = Files.createTempDirectory(Path.of("target"), "http-tomcat-");
        tomcat.setBaseDir(base.toAbsolutePath().toString());
        tomcat.setPort(0);
        tomcat.getConnector().setProperty("address", "127.0.0.1");
        var context = tomcat.addContext("", base.toAbsolutePath().toString());
        context.setParentClassLoader(getClass().getClassLoader());
        context.addServletContainerInitializer((classes, sc) -> {
            sc.setSessionTimeout(30);
            sc.setSessionTrackingModes(Set.of(SessionTrackingMode.COOKIE));
            sc.getSessionCookieConfig().setHttpOnly(true);
            sc.getSessionCookieConfig().setPath("/");
            sc.getSessionCookieConfig().setAttribute("SameSite", "Lax");
            AnnotationConfigWebApplicationContext web = new AnnotationConfigWebApplicationContext();
            web.setParent(parent);
            web.register(WebConfiguration.class, probes);
            var servlet = sc.addServlet("dispatcher", new DispatcherServlet(web));
            servlet.setLoadOnStartup(1); servlet.addMapping("/");
            sc.addFilter("encoding", new CharacterEncodingFilter("UTF-8", true))
                    .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/*");
            sc.addFilter("requestId", new RequestIdFilter())
                    .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/*");
        }, Set.of());
        try { tomcat.start(); }
        catch (Exception failure) { tomcat.destroy(); throw failure; }
    }
    public int port() { return tomcat.getConnector().getLocalPort(); }
    @Override public void close() throws Exception { try { tomcat.stop(); } finally { tomcat.destroy(); } }
}
