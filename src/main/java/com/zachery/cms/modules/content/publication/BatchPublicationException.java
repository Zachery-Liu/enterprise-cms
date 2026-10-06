package com.zachery.cms.modules.content.publication;

import com.zachery.cms.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import java.util.List;

/** Safe failures for the future HTTP adapter; no SQL or partial-success results. */
public class BatchPublicationException extends BusinessException {
    public record Failure(Long id,String code,String message) { }
    private final List<Failure> failures;
    public BatchPublicationException(HttpStatus status,List<Failure> failures) {
        super(status,"ARTICLE_BATCH_PUBLISH_FAILED","批量发布失败，整批未生效；文章 "+failures.get(0).id()+"："+failures.get(0).message());
        this.failures=List.copyOf(failures);
    }
    public List<Failure> getFailures(){return failures;}
}
