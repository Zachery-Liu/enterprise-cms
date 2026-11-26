package com.zachery.cms.modules.content.article.service;

import com.zachery.cms.common.exception.BusinessException;
import org.jsoup.Jsoup;
import org.jsoup.safety.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class ArticleContentSanitizer {
    private static final Pattern MEDIA_ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private final NamedParameterJdbcTemplate database;
    public ArticleContentSanitizer(JdbcTemplate database) {
        this.database = new NamedParameterJdbcTemplate(database);
    }

    private final Cleaner cleaner = new Cleaner(new Safelist()
            .addTags("p","br","h1","h2","h3","h4","h5","h6","strong","b","em","i","u",
                    "ul","ol","li","blockquote","code","pre","a","img")
            .addAttributes("img","src","alt","width","height")
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
        var images = document.select("img");
        if (!images.isEmpty()) {
            var request = RequestContextHolder.getRequestAttributes();
            String context = request instanceof ServletRequestAttributes attributes ? attributes.getRequest().getContextPath() : "";
            String prefix = context + "/media/images/";
            var candidates = new HashMap<String, List<org.jsoup.nodes.Element>>();
            for (var image : images) {
                String src = image.attr("src");
                if (!src.startsWith(prefix) || !MEDIA_ID.matcher(src.substring(prefix.length())).matches()) {
                    image.remove();
                    continue;
                }
                for (String attribute : List.of("width", "height")) {
                    String value = image.attr(attribute);
                    if (!value.matches("[1-9][0-9]{0,3}") || Integer.parseInt(value) > 8192) image.removeAttr(attribute);
                }
                candidates.computeIfAbsent(src.substring(prefix.length()), key -> new ArrayList<>()).add(image);
            }
            if (!candidates.isEmpty()) {
                var known = database.queryForList("SELECT id FROM cms_media_image WHERE id IN (:ids) AND storage_key=id",
                        Map.of("ids", candidates.keySet()), String.class);
                candidates.keySet().removeAll(known);
                candidates.values().forEach(group -> group.forEach(org.jsoup.nodes.Element::remove));
            }
        }
        return document.body().html();
    }
    private BusinessException invalid() {
        return new BusinessException(HttpStatus.BAD_REQUEST,"COMMON_VALIDATION_ERROR","正文必须包含可见文本，且清洗前后均不得超过 100000 字符");
    }
}
