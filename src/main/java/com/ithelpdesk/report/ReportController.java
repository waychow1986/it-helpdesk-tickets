package com.ithelpdesk.report;

import com.ithelpdesk.auth.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** 周报表页面（管理员）。 */
@Controller
public class ReportController {

    private final WeeklyReportService service;

    public ReportController(WeeklyReportService service) {
        this.service = service;
    }

    @GetMapping("/admin/reports/weekly")
    public String weekly(@AuthenticationPrincipal CurrentUser actor, Model model) {
        model.addAttribute("report", service.currentWeek(actor));
        return "admin/report";
    }
}
