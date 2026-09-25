package com.zachery.cms.modules.content.publication;

import jakarta.validation.constraints.*;
import java.time.*;
import org.springframework.format.annotation.DateTimeFormat;

public record ReviewQueueQuery(@Min(1) Integer page,@Min(1) @Max(100) Integer size,@Size(max=200) String title,
        @Positive Long categoryId,@Positive Long authorId,
        @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) OffsetDateTime submittedFrom,
        @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) OffsetDateTime submittedTo) {
    public ReviewQueueQuery {page=page==null?1:page;size=size==null?20:size;title=title==null||title.isBlank()?null:title.strip();}
    public String titlePattern() {return title==null?null:"%"+title.replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
    public LocalDateTime start() {return submittedFrom==null?null:LocalDateTime.ofInstant(submittedFrom.toInstant(),ZoneOffset.UTC);}
    public LocalDateTime end() {return submittedTo==null?null:LocalDateTime.ofInstant(submittedTo.toInstant(),ZoneOffset.UTC);}
}
