package com.zachery.cms.common.context;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain) throws ServletException, IOException {
        String id = (String) request.getAttribute(ATTRIBUTE);
        if (id == null) {
            String supplied = request.getHeader(RequestContext.HEADER);
            id = supplied != null && VALID_ID.matcher(supplied).matches()
                    ? supplied : UUID.randomUUID().toString();
            request.setAttribute(ATTRIBUTE, id);
        }
        RequestContext.set(id);
        MDC.put(RequestContext.MDC_KEY, id);
        response.setHeader(RequestContext.HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            RequestContext.clear();
            MDC.remove(RequestContext.MDC_KEY);
        }
    }

    @Override protected boolean shouldNotFilterAsyncDispatch() { return false; }
    @Override protected boolean shouldNotFilterErrorDispatch() { return false; }
}
