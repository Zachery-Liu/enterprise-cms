package com.zachery.cms.modules.content.article.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zachery.cms.common.api.PageResponse;
import com.zachery.cms.common.context.CurrentUserContext;
import com.zachery.cms.common.exception.BusinessException;
import com.zachery.cms.modules.content.article.dto.*;
import com.zachery.cms.modules.content.article.mapper.ArticleMapper;
import com.zachery.cms.modules.content.article.vo.ArticleListVO;
import com.zachery.cms.modules.identity.user.service.UserReader;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.validation.annotation.Validated;
import java.time.*;
import java.util.*;

@Service @Validated
public class ArticleQueryService {
    private final ArticleMapper articles;
    private final UserReader users;
    private final CurrentUserContext identity;
    public ArticleQueryService(ArticleMapper articles,UserReader users,CurrentUserContext identity) {
        this.articles=articles; this.users=users; this.identity=identity;
    }
    /** Count and records use a consistent snapshot; no per-article category or author lookup. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResponse<ArticleListVO> page(@NotNull @Valid ArticlePageQuery query) {
        identity.getRequiredUser();
        requireRange(query.createdFrom(),query.createdTo()); requireRange(query.updatedFrom(),query.updatedTo());
        var request=new Page<ArticlePageRow>(query.page(),query.size());
        request.setOptimizeJoinOfCountSql(false);
        var page=articles.selectArticlePage(request,query);
        if(page.getRecords().isEmpty()) return new PageResponse<>(List.of(),page.getTotal(),query.page(),query.size());
        Set<Long> ids=new HashSet<>(); page.getRecords().forEach(row->ids.add(row.getAuthorId()));
        var authors=users.findByIds(ids);
        var records=page.getRecords().stream().map(row->{
            var author=authors.get(row.getAuthorId());
            return new ArticleListVO(row.getId(),row.getTitle(),row.getSummary(),row.getCategoryId(),row.getCategoryName(),
                    row.getAuthorId(),author==null?"未知用户":author.displayName(),row.getStatus(),row.getVersion(),
                    utc(row.getCreatedAt()),utc(row.getUpdatedAt()),utc(row.getPublishedAt()));
        }).toList();
        return new PageResponse<>(records,page.getTotal(),query.page(),query.size());
    }
    private void requireRange(OffsetDateTime from,OffsetDateTime to) {
        if(from!=null&&to!=null&&!from.toInstant().isBefore(to.toInstant()))
            throw new BusinessException(HttpStatus.BAD_REQUEST,"COMMON_VALIDATION_ERROR","时间范围起点必须早于终点");
    }
    private Instant utc(LocalDateTime time) {return time==null?null:time.toInstant(ZoneOffset.UTC);}
}
