package com.zachery.cms.config;

import com.zachery.cms.modules.entry.PublicEntryController;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PublicEntryTest {
    @Test void rootRedirectUsesActualContextAndDoesNotForwardQuery() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new PublicEntryController()).build();
        for (String context : new String[]{"", "/cms", "/another-cms"}) {
            mvc.perform(get(context + "/").contextPath(context).queryParam("returnTo", "https://outside.invalid"))
                .andExpect(status().isFound()).andExpect(redirectedUrl(context + "/content/public.html#public"));
        }
    }
}
