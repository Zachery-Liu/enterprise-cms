package com.zachery.cms.modules.content.publication;

import com.zachery.cms.common.exception.BusinessException;
import com.zachery.cms.modules.content.article.enums.ArticleStatus;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

class ArticleTransitionPolicyTest {
    @TestFactory Stream<DynamicTest> allStateAndActionCombinations() {
        Map<String, ArticleStatus> allowed = Map.of(
                "DRAFT/SUBMIT", ArticleStatus.PENDING, "REJECTED/SUBMIT", ArticleStatus.PENDING,
                "PENDING/APPROVE", ArticleStatus.APPROVED, "PENDING/REJECT", ArticleStatus.REJECTED,
                "APPROVED/PUBLISH", ArticleStatus.PUBLISHED, "PUBLISHED/OFFLINE", ArticleStatus.OFFLINE,
                "OFFLINE/REOPEN", ArticleStatus.DRAFT);
        return Arrays.stream(ArticleStatus.values()).flatMap(status -> Arrays.stream(ArticleAction.values())
                .map(action -> DynamicTest.dynamicTest(status + "/" + action, () -> {
                    String key = status + "/" + action;
                    if (allowed.containsKey(key)) {
                        assertThat(ArticleTransitionPolicy.transition(status, action, "修改后重提")).isEqualTo(allowed.get(key));
                    } else {
                        assertThatThrownBy(() -> ArticleTransitionPolicy.transition(status, action, "意见"))
                                .isInstanceOfSatisfying(BusinessException.class, error -> {
                                    assertThat(error.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                                    assertThat(error.getCode()).isEqualTo("ARTICLE_STATUS_CONFLICT");
                                });
                    }
                })));
    }
    @Test void rejectionRequiresNonblankReason() {
        for (String reason : Arrays.asList(null, "", " \t\n", "　")) {
            assertThatThrownBy(() -> ArticleTransitionPolicy.transition(ArticleStatus.PENDING, ArticleAction.REJECT, reason))
                    .isInstanceOfSatisfying(BusinessException.class, error ->
                            assertThat(error.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
    }
    @Test void enforcesCommentLengthAndAllowsOptionalApprovalComment() {
        assertThat(ArticleTransitionPolicy.transition(ArticleStatus.PENDING, ArticleAction.APPROVE, null))
                .isEqualTo(ArticleStatus.APPROVED);
        assertThat(ArticleTransitionPolicy.transition(ArticleStatus.PENDING, ArticleAction.REJECT, "原".repeat(500)))
                .isEqualTo(ArticleStatus.REJECTED);
        assertThatThrownBy(() -> ArticleTransitionPolicy.transition(ArticleStatus.PENDING, ArticleAction.REJECT, "原".repeat(501)))
                .isInstanceOf(BusinessException.class);
    }
    @Test void editableDeletableAndPublicSetsAreExplicit() {
        assertThat(Arrays.stream(ArticleStatus.values()).filter(ArticleStatus::isEditable))
                .containsExactly(ArticleStatus.DRAFT, ArticleStatus.REJECTED);
        assertThat(Arrays.stream(ArticleStatus.values()).filter(ArticleStatus::isDeletable))
                .containsExactly(ArticleStatus.DRAFT, ArticleStatus.REJECTED, ArticleStatus.OFFLINE);
        assertThat(Arrays.stream(ArticleStatus.values()).filter(ArticleStatus::isPubliclyVisible))
                .containsExactly(ArticleStatus.PUBLISHED);
    }
}
