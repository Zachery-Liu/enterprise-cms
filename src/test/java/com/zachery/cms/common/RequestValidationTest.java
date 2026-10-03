package com.zachery.cms.common;

import com.zachery.cms.common.api.*;
import com.zachery.cms.common.exception.RequestValidationException;
import com.zachery.cms.common.validation.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RequestValidationTest {
    @Test
    void pageDefaultsAndBoundaryAreSupported() {
        assertThat(PageQuery.parse(Map.of())).isEqualTo(new PageQuery(1, 20));
        assertThat(new PageQuery(2, 100).offset()).isEqualTo(100);
        assertThat(PageResponse.empty(1, 20).records()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "101", "abc", "", "9223372036854775808"})
    void invalidPageSizeIsRejected(String size) {
        assertThatThrownBy(() -> PageQuery.parse(Map.of("size", size)))
                .isInstanceOf(RequestValidationException.class);
    }

    @Test
    void invalidPageAndOverflowAreRejected() {
        assertThatThrownBy(() -> new PageQuery(0, 20)).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> new PageQuery(Long.MAX_VALUE, 100)).isInstanceOf(RequestValidationException.class);
    }

    @Test
    void batchSizeIsCheckedBeforeDeduplicationAndEmptyReplacementIsAllowed() {
        assertThat(RequestValidation.ids(List.of(1L, 1L, 2L), "ids", 3)).containsExactly(1L, 2L);
        assertThat(RequestValidation.ids(List.of(), "ids", 3, true)).isEmpty();
        assertThatThrownBy(() -> RequestValidation.ids(List.of(), "ids", 3)).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> RequestValidation.ids(List.of(1L, 1L, 1L), "ids", 2)).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> RequestValidation.ids(Arrays.asList(1L, null), "ids", 2)).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> RequestValidation.ids(List.of(0L), "ids", 2)).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> RequestValidation.ids(null, "ids", 2)).isInstanceOf(RequestValidationException.class);
    }

    @Test
    void textLengthsRespectEndpointLimits() {
        assertThat(RequestValidation.text("abcd", "name", 1, 4)).isEqualTo("abcd");
        assertThatThrownBy(() -> RequestValidation.text("", "name", 1, 4)).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> RequestValidation.text("abcde", "name", 1, 4)).isInstanceOf(RequestValidationException.class);
    }

    @Test
    void offsetTimesNormalizeToUtcAndReversedRangesAreRejected() {
        Instant utc = Instant.parse("2026-10-04T00:00:00Z");
        assertThat(RequestValidation.time("2026-10-04T08:00:00+08:00", "from")).isEqualTo(utc);
        assertThat(RequestValidation.time(null, "from")).isNull();
        assertThatThrownBy(() -> RequestValidation.time("2026-10-04T08:00:00", "from")).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> RequestValidation.timeRange(utc.plusSeconds(1), utc)).isInstanceOf(RequestValidationException.class);
        RequestValidation.timeRange(utc, utc);
    }

    @Test
    void responseListsAreSnapshots() {
        var source = new ArrayList<String>(List.of("first"));
        var page = new PageResponse<>(source, 1, 1, 20);
        source.clear();
        assertThat(page.records()).containsExactly("first");
        assertThatThrownBy(() -> page.records().add("second")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void sortUsesServerColumnsAndRejectsInjectionDuplicatesAndUnknownDirections() {
        Map<String, String> columns = Map.of("createdAt", "created_at", "id", "id");
        assertThat(RequestValidation.sort("createdAt:desc,id:asc", columns))
                .containsExactly(new RequestValidation.SortField("created_at", false),
                        new RequestValidation.SortField("id", true));
        for (String sort : List.of("password_hash:asc", "id:desc;DROP TABLE", "id:asc,id:desc", "id:up", "id:asc,"))
            assertThatThrownBy(() -> RequestValidation.sort(sort, columns)).isInstanceOf(RequestValidationException.class);
    }

    @Test
    void singleIdRejectsNonPositiveMalformedAndOverflowValues() {
        assertThat(RequestValidation.positiveId("123", "id")).isEqualTo(123);
        for (String id : List.of("0", "-1", "abc", "9223372036854775808"))
            assertThatThrownBy(() -> RequestValidation.positiveId(id, "id")).isInstanceOf(RequestValidationException.class);
    }
}
