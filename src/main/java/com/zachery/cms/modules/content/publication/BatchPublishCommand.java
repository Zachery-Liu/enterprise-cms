package com.zachery.cms.modules.content.publication;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;

public record BatchPublishCommand(@NotNull @Size(min=1,max=50) List<@NotNull @Valid Item> items) {
    public BatchPublishCommand {items=items==null?null:Collections.unmodifiableList(new ArrayList<>(items));}
    public record Item(@NotNull @Positive Long id,@NotNull @PositiveOrZero Integer version) { }
}
