package com.zachery.cms.modules.content.publication;

import org.springframework.stereotype.Service;

/** Delegates to the transactional state owner; no independent state writes or per-item transactions. */
@Service
public class PublicationBatchService {
    private final ArticleLifecycleService lifecycle;
    public PublicationBatchService(ArticleLifecycleService lifecycle){this.lifecycle=lifecycle;}
    public BatchPublishResult publish(BatchPublishCommand command){return lifecycle.publishBatch(command);}
}
