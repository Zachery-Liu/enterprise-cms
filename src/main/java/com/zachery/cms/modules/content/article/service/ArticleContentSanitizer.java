package com.zachery.cms.modules.content.article.service;

import com.zachery.cms.common.exception.BusinessException;
import org.jsoup.Jsoup;
import org.jsoup.safety.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.Locale;

@Component
public class ArticleContentSanitizer {
    private final Cleaner cleaner = new Cleaner(new Safelist()
            .addTags("p","br","h1","h2","h3","h4","h5","h6","strong","b","em","i","u",
                    "ul","ol","li","blockquote","code","pre","a")
            .addAttributes("a","href","title").addProtocols("a","href","http","https")
            .addEnforcedAttribute("a","rel","nofollow noopener noreferrer"));

    public String forStorage(String html) {
        if (html == null || html.length() > 100000) throw invalid();
        String safe = forDisplay(html);
        String visible = Jsoup.parseBodyFragment(safe).text();
        if (safe.length() > 100000 || visible.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c)
                || Character.getType(c) == Character.FORMAT)) throw invalid();
        return safe;
    }

    public String forDisplay(String html) {
        var document = cleaner.clean(Jsoup.parseBodyFragment(html == null ? "" : html));
        document.outputSettings().prettyPrint(false);
        for (var anchor : document.select("a[href]")) {
            try {
                URI uri = URI.create(anchor.attr("href"));
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) anchor.removeAttr("href");
            } catch (IllegalArgumentException exception) { anchor.removeAttr("href"); }
        }
        return document.body().html();
    }
    private BusinessException invalid() {
        return new BusinessException(HttpStatus.BAD_REQUEST,"COMMON_VALIDATION_ERROR","正文必须包含可见文本，且清洗前后均不得超过 100000 字符");
    }
}
