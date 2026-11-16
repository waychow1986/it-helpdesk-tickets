package com.ithelpdesk.user;

import com.ithelpdesk.auth.CurrentUser;
import com.ithelpdesk.web.ValidationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 账号管理页面（FR-001、FR-004）。 */
@Controller
public class UserAdminController {

    private final UserAdminService service;

    public UserAdminController(UserAdminService service) {
        this.service = service;
    }

    @GetMapping("/admin/users")
    public String list(@AuthenticationPrincipal CurrentUser actor, Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new UserForm());
        }
        return render(actor, model);
    }

    @PostMapping("/admin/users")
    public String create(@AuthenticationPrincipal CurrentUser actor, @ModelAttribute("form") UserForm form,
                         Model model, RedirectAttributes redirect) {
        try {
            service.createUser(actor, form);
        } catch (ValidationException e) {
            model.addAttribute("form", form.forRedisplay());
            model.addAttribute("errors", e.getErrors());
            return render(actor, model);
        }
        redirect.addFlashAttribute("flash", "账号已创建");
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/users/{id}/role")
    public String changeRole(@AuthenticationPrincipal CurrentUser actor, @PathVariable("id") long id,
                             @RequestParam(name = "role", required = false) String role,
                             RedirectAttributes redirect) {
        try {
            service.changeRole(actor, id, role);
            redirect.addFlashAttribute("flash", "身份已更新");
        } catch (ValidationException e) {
            redirect.addFlashAttribute("flashError", e.firstMessage());
        }
        return "redirect:/admin/users";
    }

    private String render(CurrentUser actor, Model model) {
        model.addAttribute("users", service.listUsers(actor));
        model.addAttribute("roles", Role.values());
        return "admin/users";
    }
}
