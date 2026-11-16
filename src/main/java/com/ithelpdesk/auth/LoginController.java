package com.ithelpdesk.auth;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 登录页（无注册入口，FR-001、FR-002）。 */
@Controller
public class LoginController {

    @GetMapping("/login")
    public String login(@AuthenticationPrincipal CurrentUser user) {
        if (user != null) {
            return "redirect:/";
        }
        return "login";
    }
}
