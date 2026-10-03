package com.zachery.cms.common;

import com.zachery.cms.common.context.*;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class RequestIdFilterTest {
    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void exceptionalExitClearsContextAndDoesNotPolluteNextRequest() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "exception-request");
        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            assertThat(RequestContext.requestId()).isEqualTo("exception-request");
            assertThat(MDC.get("requestId")).isEqualTo("exception-request");
            throw new ServletException("test failure");
        })).isInstanceOf(ServletException.class);
        assertThat(MDC.get("requestId")).isNull();
        assertThat(RequestContext.requestId()).isNotEqualTo("exception-request");
        var next = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        filter.doFilter(next, response, (req, res) -> assertThat(RequestContext.requestId()).isNotEqualTo("exception-request"));
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void concurrentRequestsHaveIndependentContextAndMdc() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CyclicBarrier barrier = new CyclicBarrier(4);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                String id = "parallel-" + i;
                tasks.add(() -> {
                    var request = new MockHttpServletRequest();
                    request.addHeader("X-Request-Id", id);
                    var response = new MockHttpServletResponse();
                    filter.doFilter(request, response, (req, res) -> {
                        try { barrier.await(5, TimeUnit.SECONDS); }
                        catch (Exception e) { throw new ServletException(e); }
                        assertThat(RequestContext.requestId()).isEqualTo(id);
                        assertThat(MDC.get("requestId")).isEqualTo(id);
                    });
                    assertThat(MDC.get("requestId")).isNull();
                    assertThat(RequestContext.requestId()).isNotEqualTo(id);
                    return response.getHeader("X-Request-Id");
                });
            }
            Set<String> ids = new HashSet<>();
            for (Future<String> result : pool.invokeAll(tasks)) ids.add(result.get());
            assertThat(ids).hasSize(4);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void acceptsMaxLengthAndReusesIdOnErrorRedispatch() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "a".repeat(64));
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {});
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("a".repeat(64));
        request.setDispatcherType(jakarta.servlet.DispatcherType.ERROR);
        var errorResponse = new MockHttpServletResponse();
        filter.doFilter(request, errorResponse, (req, res) ->
                assertThat(RequestContext.requestId()).isEqualTo("a".repeat(64)));
        assertThat(errorResponse.getHeader("X-Request-Id")).isEqualTo(response.getHeader("X-Request-Id"));
        assertThat(MDC.get("requestId")).isNull();
    }
}
