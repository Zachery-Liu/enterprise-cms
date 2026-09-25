package com.zachery.cms.modules.content.publication;

import jakarta.validation.constraints.*;

public record PublicationPageQuery(@Min(1) Integer page,@Min(1) @Max(100) Integer size,
        @Size(max=200) String title,@Positive Long categoryId) {
    public PublicationPageQuery {page=page==null?1:page;size=size==null?20:size;title=title==null||title.isBlank()?null:title.strip();}
    public String titlePattern() {return title==null?null:"%"+title.replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
}
