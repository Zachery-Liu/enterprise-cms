package com.zachery.cms.modules.content.publication;

import java.util.List;

public record BatchPublishResult(int count,List<TransitionResult> items) {
    public BatchPublishResult {items=List.copyOf(items);}
}
