package com.ithelpdesk.user;

import com.ithelpdesk.auth.CurrentUser;
import com.ithelpdesk.web.BadRequestException;
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

/** 账号管理页面（V1 FR-001、FR-004；V2 停用 / 启用 FR-007、FR-008、FR-014、FR-015，TD-027）。 */
@Controller
public class UserAdminController {

    private final UserAdminService service;
    private final AccountStatusService accountStatus;

    public UserAdminController(UserAdminService service, AccountStatusService accountStatus) {
        this.service = service;
        this.accountStatus = accountStatus;
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

    /** 停用账号；管理员成功停用自己时直接渲染结果页（下一次请求由 RoleRefreshFilter 引导至登录入口）。 */
    @PostMapping("/admin/users/{id}/deactivate")
    public String deactivate(@AuthenticationPrincipal CurrentUser actor, @PathVariable("id") String id, Model model,
                             RedirectAttributes redirect) {
        long targetId = parsePositiveId(id);
        try {
            AccountStatusService.Result result = accountStatus.deactivate(actor, targetId);
            if (result.changed() && actor != null && actor.getId() == targetId) {
                model.addAttribute("flash", result.message());
                model.addAttribute("form", new UserForm());
                return render(actor, model);
            }
            redirect.addFlashAttribute("flash", result.message());
        } catch (ValidationException e) {
            redirect.addFlashAttribute("flashError", e.firstMessage());
        }
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/users/{id}/activate")
    public String activate(@AuthenticationPrincipal CurrentUser actor, @PathVariable("id") String id,
                           RedirectAttributes redirect) {
        long targetId = parsePositiveId(id);
        try {
            redirect.addFlashAttribute("flash", accountStatus.activate(actor, targetId).message());
        } catch (ValidationException e) {
            redirect.addFlashAttribute("flashError", e.firstMessage());
        }
        return "redirect:/admin/users";
    }

    /** 路径中的账号 id 必须是正整数，否则 400。 */
    static long parsePositiveId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,17}")) {
            throw new BadRequestException("id");
        }
        return Long.parseLong(value);
    }

    private String render(CurrentUser actor, Model model) {
        model.addAttribute("users", service.listUsers(actor));
        model.addAttribute("roles", Role.values());
        return "admin/users";
    }
}
