package com.zachery.cms.config;

import com.zachery.cms.common.context.RequestIdFilter;
import jakarta.servlet.*;
import org.springframework.web.filter.CharacterEncodingFilter;
import org.springframework.web.servlet.support.AbstractAnnotationConfigDispatcherServletInitializer;
import org.springframework.web.context.support.StandardServletEnvironment;
import org.springframework.core.io.support.ResourcePropertySource;
import java.io.IOException;
import java.util.*;

public class CmsWebInitializer extends AbstractAnnotationConfigDispatcherServletInitializer {
    @Override protected Class<?>[] getRootConfigClasses() { return new Class<?>[]{RootConfiguration.class}; }
    @Override protected Class<?>[] getServletConfigClasses() { return new Class<?>[]{WebConfiguration.class}; }
    @Override protected String[] getServletMappings() { return new String[]{"/"}; }
    @Override public void onStartup(ServletContext context) throws ServletException {
        StandardServletEnvironment env = new StandardServletEnvironment();
        env.initPropertySources(context, null);
        try {
            String external = env.getProperty("cms.config");
            if (external != null) env.getPropertySources().addLast(new ResourcePropertySource(external));
            env.getPropertySources().addLast(new ResourcePropertySource("classpath:cms.properties"));
        } catch (IOException e) { throw new ServletException("Cannot load CMS configuration", e); }
        context.setSessionTimeout(env.getProperty("cms.session.timeout-minutes", Integer.class, 30));
        context.setSessionTrackingModes(Set.of(SessionTrackingMode.COOKIE));
        SessionCookieConfig cookie = context.getSessionCookieConfig();
        cookie.setHttpOnly(true);
        cookie.setSecure(env.getProperty("cms.session.cookie-secure", Boolean.class, false));
        cookie.setPath(context.getContextPath().isEmpty() ? "/" : context.getContextPath());
        cookie.setAttribute("SameSite", "Lax");
        FilterRegistration.Dynamic encoding = context.addFilter("encoding", new CharacterEncodingFilter("UTF-8", true));
        encoding.setAsyncSupported(true);
        encoding.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR), false, "/*");
        FilterRegistration.Dynamic requestId = context.addFilter("requestId", new RequestIdFilter());
        requestId.setAsyncSupported(true);
        requestId.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR), true, "/*");
        super.onStartup(context);
    }
}
