package com.ithelpdesk.ticket;

import com.ithelpdesk.auth.CurrentUser;
import com.ithelpdesk.category.CategoryService;
import com.ithelpdesk.config.AppProperties;
import com.ithelpdesk.web.ValidationException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 工单页面：列表 / 搜索 / 筛选、提交、详情、补充说明 / 回复、认领、分派、改状态。
 * V2：每个页面请求开始时取一次 clock.instant() 作为本页 now，超时标识只调用 Overdue 判定（TD-021）。
 */
@Controller
public class TicketController {

    private final TicketQueryService queries;
    private final TicketCommandService commands;
    private final CategoryService categories;
    private final AppProperties properties;
    private final Clock clock;

    public TicketController(TicketQueryService queries, TicketCommandService commands, CategoryService categories,
                            AppProperties properties, Clock clock) {
        this.queries = queries;
        this.commands = commands;
        this.categories = categories;
        this.properties = properties;
        this.clock = clock;
    }

    @GetMapping("/tickets")
    public String list(@AuthenticationPrincipal CurrentUser actor,
                       @RequestParam(name = "q", required = false) String q,
                       @RequestParam(name = "status", required = false) String status,
                       @RequestParam(name = "categoryId", required = false) String categoryId,
                       @RequestParam(name = "assignee", required = false) String assignee,
                       @RequestParam(name = "from", required = false) String from,
                       @RequestParam(name = "to", required = false) String to,
                       @RequestParam(name = "page", required = false) String page,
                       Model model) {
        Instant now = clock.instant();
        TicketSearchCriteria criteria = TicketSearchCriteria.parse(q, status, categoryId, assignee, from, to, page,
            properties.zoneId());
        TicketPage result = queries.search(actor, criteria).withOverdue(now);
        model.addAttribute("criteria", criteria);
        model.addAttribute("result", result);
        model.addAttribute("pageTitle", actor.isEmployee() ? "我的工单" : "全部工单");
        model.addAttribute("statuses", TicketStatus.values());
        model.addAttribute("categoryOptions", categories.listAllForFilter());
        model.addAttribute("assigneeOptions", queries.assigneeOptions(actor));
        model.addAttribute("prevUrl", pageUrl(criteria, criteria.getPage() - 1));
        model.addAttribute("nextUrl", pageUrl(criteria, criteria.getPage() + 1));
        return "tickets/list";
    }

    @GetMapping("/tickets/new")
    public String newTicket(Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new TicketForm());
        }
        return renderNew(model);
    }

    @PostMapping("/tickets")
    public String submit(@AuthenticationPrincipal CurrentUser actor, @ModelAttribute("form") TicketForm form,
                         Model model, RedirectAttributes redirect) {
        long id;
        try {
            id = commands.submit(actor, form);
        } catch (ValidationException e) {
            model.addAttribute("errors", e.getErrors());
            return renderNew(model);
        }
        redirect.addFlashAttribute("flash", "工单已提交");
        return "redirect:/tickets/" + id;
    }

    @GetMapping("/tickets/{id}")
    public String detail(@AuthenticationPrincipal CurrentUser actor, @PathVariable("id") long id, Model model) {
        return renderDetail(actor, id, model);
    }

    @PostMapping("/tickets/{id}/comments")
    public String comment(@AuthenticationPrincipal CurrentUser actor, @PathVariable("id") long id,
                          @RequestParam(name = "body", required = false) String body, Model model,
                          RedirectAttributes redirect) {
        try {
            commands.addComment(actor, id, body);
        } catch (ValidationException e) {
            model.addAttribute("commentErrors", e.getErrors());
            model.addAttribute("commentBody", body);
            return renderDetail(actor, id, model);
        }
        redirect.addFlashAttribute("flash", actor.isEmployee() ? "补充说明已添加" : "回复已添加");
        return "redirect:/tickets/" + id;
    }

    @PostMapping("/tickets/{id}/claim")
    public String claim(@AuthenticationPrincipal CurrentUser actor, @PathVariable("id") long id,
                        RedirectAttributes redirect) {
        redirect.addFlashAttribute("flash", commands.claim(actor, id));
        return "redirect:/tickets/" + id;
    }

    @PostMapping("/tickets/{id}/assign")
    public String assign(@AuthenticationPrincipal CurrentUser actor, @PathVariable("id") long id,
                         @RequestParam(name = "assigneeId", required = false) String assigneeId,
                         RedirectAttributes redirect) {
        try {
            redirect.addFlashAttribute("flash", commands.assign(actor, id, assigneeId));
        } catch (ValidationException e) {
            redirect.addFlashAttribute("flashError", e.firstMessage());
        }
        return "redirect:/tickets/" + id;
    }

    @PostMapping("/tickets/{id}/status")
    public String status(@AuthenticationPrincipal CurrentUser actor, @PathVariable("id") long id,
                         @RequestParam(name = "status", required = false) String status,
                         RedirectAttributes redirect) {
        redirect.addFlashAttribute("flash", commands.changeStatus(actor, id, status));
        return "redirect:/tickets/" + id;
    }

    private String renderNew(Model model) {
        model.addAttribute("categories", categories.listActive());
        model.addAttribute("urgencies", Urgency.values());
        return "tickets/new";
    }

    private String renderDetail(CurrentUser actor, long id, Model model) {
        Instant now = clock.instant();
        TicketDetail detail = queries.detail(actor, id).withOverdue(now);
        model.addAttribute("detail", detail);
        model.addAttribute("ticket", detail.ticket());
        model.addAttribute("statuses", TicketStatus.values());
        model.addAttribute("assignable", queries.assignableStaff(actor));
        return "tickets/detail";
    }

    static String pageUrl(TicketSearchCriteria criteria, int page) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/tickets");
        for (Map.Entry<String, String> entry : criteria.queryParams().entrySet()) {
            builder.queryParam(entry.getKey(), entry.getValue());
        }
        if (page > 1) {
            builder.queryParam("page", page);
        }
        return builder.encode().build().toUriString();
    }
}
