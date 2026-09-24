package com.zachery.cms.common.context;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RequestIdFilterTest {
    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearRequestContext() {
        RequestIdContext.clear();
    }

    @Test
    void acceptsValidIdAndMakesItAvailableThroughRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdContext.HEADER_NAME, "client_req-7");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> assertEquals("client_req-7", RequestIdContext.getRequestId());

        filter.doFilter(request, response, chain);

        assertEquals("client_req-7", response.getHeader(RequestIdContext.HEADER_NAME));
        assertNull(RequestIdContext.getRequestId());
    }

    @Test
    void replacesInvalidIdWithGeneratedUuidAndClearsContextAfterFailure() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdContext.HEADER_NAME, "bad id;value");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            assertDoesNotThrow(() -> UUID.fromString(RequestIdContext.getRequestId()));
            throw new IllegalStateException("downstream failure");
        };

        assertThrows(IllegalStateException.class, () -> filter.doFilter(request, response, chain));

        assertDoesNotThrow(() -> UUID.fromString(response.getHeader(RequestIdContext.HEADER_NAME)));
        assertNull(RequestIdContext.getRequestId());
    }
}
