package com.zachery.cms.modules.content.article;

import com.zachery.cms.common.exception.BusinessException;
import com.zachery.cms.modules.content.article.service.ArticleContentSanitizer;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.assertj.core.api.Assertions.*;

class ArticleContentSanitizerTest {
    private final ArticleContentSanitizer sanitizer=new ArticleContentSanitizer();
    @Test void keepsBasicFormattingButRemovesActiveContentAndUnknownAttributes() {
        String safe=sanitizer.forStorage("<h2 style='color:red'>标题</h2><p onclick='bad()'>正文<strong>强调</strong>"
                +"<script>alert(1)</script><iframe src='https://evil.test'></iframe><img src=x onerror=bad()>"
                +"<svg onload=bad()></svg><object data=x></object></p>");
        assertThat(safe).contains("<h2>标题</h2>","<strong>强调</strong>")
                .doesNotContain("script","onclick","onerror","iframe","style=","<img","<svg","<object","alert(1)");
        assertThat(sanitizer.forStorage(safe)).isEqualTo(safe);
    }
    @Test void onlyAllowsAbsoluteHttpAndHttpsLinks() {
        for (String url : Arrays.asList("javascript:alert(1)","javas&#99;ript:alert(1)","data:text/html,bad", "//evil.test","/relative","mailto:a@b.test")) {
            String safe=sanitizer.forStorage("<p><a href='"+url+"' target='_blank'>链接</a></p>");
            assertThat(Jsoup.parseBodyFragment(safe).select("a[href]")).isEmpty();
        }
        String safe=sanitizer.forStorage("<a href='https://example.com/x' onclick='bad()'>安全链接</a>");
        assertThat(safe).contains("href=\"https://example.com/x\"","nofollow noopener noreferrer").doesNotContain("onclick");
    }
    @Test void rejectsInvisibleOrOversizedContentIncludingCleaningExpansion() {
        for (String html : Arrays.asList(null,"","<p>　&nbsp;\u200b</p>","<script>bad()</script>","x".repeat(100001),"&".repeat(30000))) {
            assertThatThrownBy(() -> sanitizer.forStorage(html)).isInstanceOf(BusinessException.class);
        }
        assertThat(sanitizer.forStorage("x".repeat(100000))).hasSize(100000);
    }
}
