package com.zachery.cms.modules.content.publication;

import com.zachery.cms.common.exception.BusinessException;
import com.zachery.cms.modules.content.article.enums.ArticleStatus;
import org.springframework.http.HttpStatus;
import java.util.Objects;

/** Pure state rules. The caller must also check identity, category, version and transaction results. */
public final class ArticleTransitionPolicy {
    private ArticleTransitionPolicy() { }

    public static ArticleStatus transition(ArticleStatus current, ArticleAction action, String comment) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(action, "action");
        ArticleStatus target = switch (action) {
            case SUBMIT -> current == ArticleStatus.DRAFT || current == ArticleStatus.REJECTED
                    ? ArticleStatus.PENDING : null;
            case APPROVE -> current == ArticleStatus.PENDING ? ArticleStatus.APPROVED : null;
            case REJECT -> current == ArticleStatus.PENDING ? ArticleStatus.REJECTED : null;
            case PUBLISH -> current == ArticleStatus.APPROVED ? ArticleStatus.PUBLISHED : null;
            case OFFLINE -> current == ArticleStatus.PUBLISHED ? ArticleStatus.OFFLINE : null;
            case REOPEN -> current == ArticleStatus.OFFLINE ? ArticleStatus.DRAFT : null;
        };
        if (target == null) {
            throw new BusinessException(HttpStatus.CONFLICT, "ARTICLE_STATUS_CONFLICT", "当前文章状态不允许此操作");
        }
        String normalized = comment == null ? null : comment.strip();
        if ((action == ArticleAction.REJECT && (normalized == null || normalized.isEmpty()))
                || (comment != null && comment.length() > 500)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "COMMON_VALIDATION_ERROR", "审核意见最多 500 字符，退回原因不能为空");
        }
        return target;
    }
}
