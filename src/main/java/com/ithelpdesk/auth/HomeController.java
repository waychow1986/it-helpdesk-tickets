package com.ithelpdesk.auth;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 首页按身份重定向（FR-003）。 */
@Controller
public class HomeController {

    @GetMapping("/")
    public String home(@AuthenticationPrincipal CurrentUser user) {
        if (user == null) {
            return "redirect:/login";
        }
        return user.isAdmin() ? "redirect:/admin/users" : "redirect:/tickets";
    }
}
