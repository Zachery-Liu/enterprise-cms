package com.zachery.cms.common.api;

import com.zachery.cms.common.context.RequestIdContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ApiResponseTest {
    @AfterEach
    void clearRequestContext() {
        RequestIdContext.clear();
    }

    @Test
    void successResponseUsesContractCodeAndCurrentRequestId() {
        RequestIdContext.setRequestId("request_123");

        ApiResponse<String> response = ApiResponse.success("loaded", "value");

        assertEquals("OK", response.code());
        assertEquals("loaded", response.message());
        assertEquals("value", response.data());
        assertEquals("request_123", response.requestId());
    }

    @Test
    void pageResponseNormalizesNullRecordsAndCopiesInput() {
        assertEquals(List.of(), PageResponse.of(null, 0, 1, 20).records());

        List<String> source = new ArrayList<>(List.of("first"));
        PageResponse<String> page = PageResponse.of(source, 1, 1, 20);
        source.add("second");

        assertEquals(List.of("first"), page.records());
        assertThrows(UnsupportedOperationException.class, () -> page.records().add("third"));
    }
}
