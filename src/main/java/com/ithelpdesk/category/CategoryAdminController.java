package com.ithelpdesk.category;

import com.ithelpdesk.auth.CurrentUser;
import com.ithelpdesk.web.ValidationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 类别管理页面（FR-005、FR-006）。 */
@Controller
public class CategoryAdminController {

    private final CategoryService service;

    public CategoryAdminController(CategoryService service) {
        this.service = service;
    }

    @GetMapping("/admin/categories")
    public String list(@AuthenticationPrincipal CurrentUser actor, Model model) {
        model.addAttribute("categories", service.listAll(actor));
        return "admin/categories";
    }

    @PostMapping("/admin/categories")
    public String create(@AuthenticationPrincipal CurrentUser actor,
                         @RequestParam(name = "name", required = false) String name, Model model,
                         RedirectAttributes redirect) {
        try {
            service.create(actor, name);
        } catch (ValidationException e) {
            model.addAttribute("errors", e.getErrors());
            model.addAttribute("newName", name);
            return list(actor, model);
        }
        redirect.addFlashAttribute("flash", "类别已新增");
        return "redirect:/admin/categories";
    }

    @PostMapping("/admin/categories/{id}/rename")
    public String rename(@AuthenticationPrincipal CurrentUser actor, @PathVariable("id") long id,
                         @RequestParam(name = "name", required = false) String name,
                         RedirectAttributes redirect) {
        try {
            service.rename(actor, id, name);
            redirect.addFlashAttribute("flash", "类别已改名");
        } catch (ValidationException e) {
            redirect.addFlashAttribute("flashError", e.firstMessage());
        }
        return "redirect:/admin/categories";
    }

    @PostMapping("/admin/categories/{id}/remove")
    public String remove(@AuthenticationPrincipal CurrentUser actor, @PathVariable("id") long id,
                         RedirectAttributes redirect) {
        try {
            service.remove(actor, id);
            redirect.addFlashAttribute("flash", "类别已移除");
        } catch (ValidationException e) {
            redirect.addFlashAttribute("flashError", e.firstMessage());
        }
        return "redirect:/admin/categories";
    }
}
