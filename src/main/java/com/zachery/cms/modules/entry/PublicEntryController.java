package com.zachery.cms.modules.entry;

import com.zachery.cms.security.annotation.AnonymousAccess;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.view.RedirectView;

/** Resolve the site's entry relative to the container's actual deployment context. */
@Controller
public class PublicEntryController {
    @AnonymousAccess
    @GetMapping({"", "/"})
    public RedirectView home(HttpServletRequest request) {
        var redirect = new RedirectView(request.getContextPath() + "/content/public.html#public");
        redirect.setExposeModelAttributes(false);
        return redirect;
    }
}
