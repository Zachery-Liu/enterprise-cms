package com.zachery.cms.modules.content.article.dto;

import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import org.springframework.format.annotation.DateTimeFormat;

public record ArticlePageQuery(
        @Min(1) Integer page, @Min(1) @Max(100) Integer size, @Size(max=200) String title,
        @Positive Long categoryId, @Pattern(regexp="DRAFT|PENDING|REJECTED|APPROVED|PUBLISHED|OFFLINE") String status,
        @Positive Long authorId,
        @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) OffsetDateTime createdFrom,
        @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) OffsetDateTime createdTo,
        @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) OffsetDateTime updatedFrom,
        @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) OffsetDateTime updatedTo,
        @Pattern(regexp="createdAt|updatedAt|publishedAt|title") String sort,
        @Pattern(regexp="ASC|DESC") String direction) {
    public ArticlePageQuery {
        page=page==null?1:page; size=size==null?20:size;
        title=title==null||title.isBlank()?null:title.strip();
        sort=sort==null?"updatedAt":sort; direction=direction==null?"DESC":direction;
    }
    /** Treat user keywords literally, including LIKE metacharacters. */
    public String getTitlePattern() {
        return title==null?null:"%"+title.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
    }
    public java.time.LocalDateTime getCreatedStart() {return utc(createdFrom);}
    public java.time.LocalDateTime getCreatedEnd() {return utc(createdTo);}
    public java.time.LocalDateTime getUpdatedStart() {return utc(updatedFrom);}
    public java.time.LocalDateTime getUpdatedEnd() {return utc(updatedTo);}
    private static java.time.LocalDateTime utc(OffsetDateTime time) {
        return time==null?null:java.time.LocalDateTime.ofInstant(time.toInstant(),java.time.ZoneOffset.UTC);
    }
}
