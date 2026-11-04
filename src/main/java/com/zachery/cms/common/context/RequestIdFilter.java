package com.zachery.cms.common.context;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
    private static final Pattern VALID_REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String incoming = request.getHeader(RequestIdContext.HEADER_NAME);
        String requestId = incoming != null && VALID_REQUEST_ID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();

        RequestIdContext.setRequestId(requestId);
        response.setHeader(RequestIdContext.HEADER_NAME, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            RequestIdContext.clear();
        }
    }
}
