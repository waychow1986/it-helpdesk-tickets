package com.ithelpdesk.auth;

import com.ithelpdesk.user.InitialAdminSetupService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** 登录页（无注册入口，FR-001、FR-002）；尚无可用管理员时显示“设置初始管理员”入口（FR-031）。 */
@Controller
public class LoginController {

    private final InitialAdminSetupService setupService;

    public LoginController(InitialAdminSetupService setupService) {
        this.setupService = setupService;
    }

    @GetMapping("/login")
    public String login(@AuthenticationPrincipal CurrentUser user, Model model) {
        if (user != null) {
            return "redirect:/";
        }
        model.addAttribute("setupOpen", setupService.isSetupOpen());
        return "login";
    }
}
