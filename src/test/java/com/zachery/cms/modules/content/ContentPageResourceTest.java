package com.zachery.cms.modules.content;

import com.zachery.cms.config.RootConfiguration;
import com.zachery.cms.config.WebConfiguration;
import com.zachery.cms.support.TestDatabaseConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitWebConfig({RootConfiguration.class, WebConfiguration.class, TestDatabaseConfiguration.class})
@WebAppConfiguration("src/main/webapp")
@org.springframework.test.context.TestPropertySource(locations="classpath:cms-test.properties", properties="cms.db.url=jdbc:h2:mem:a_resources;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class ContentPageResourceTest {
    @Autowired WebApplicationContext context;

    @Test void servesOnlyStaticPageResourcesWithoutOpeningContentApiRoutes() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        var page = mvc.perform(get("/content/index.html")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("内容管理"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        mvc.perform(get("/content/public.html")).andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("公开内容"));
        var asset = Pattern.compile("src=\"(\\./assets/[^\"]+\\.js)\"").matcher(page);
        assertThat(asset.find()).as("Run npm ci and npm run build in frontend before Maven").isTrue();
        mvc.perform(get("/content/" + asset.group(1).substring(2))).andExpect(status().isOk());
        mvc.perform(get("/api/articles")).andExpect(status().isUnauthorized());
        mvc.perform(get("/content/missing.html")).andExpect(status().isNotFound());
        mvc.perform(get("/content/../WEB-INF/web.xml")).andExpect(status().isNotFound());
    }
}
