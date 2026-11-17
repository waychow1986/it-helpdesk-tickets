package com.ithelpdesk.user;

import com.ithelpdesk.web.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * 登录入口的一次性“设置初始管理员”流程（FR-031、TD-011、TD-019）。
 * 判定顺序：关闭 → 403；来源地址限流 → 429；口令未配置/为空/错误 → 200 重渲染并计一次失败；口令通过后才进入写事务。
 */
@Controller
public class InitialAdminSetupController {

    static final String MSG_TOKEN_MISSING = "此部署未提供设置口令，暂不能完成设置";
    static final String MSG_TOKEN_EMPTY = "请填写设置口令";
    static final String MSG_TOKEN_WRONG = "设置口令不正确";

    private static final Logger LOG = LoggerFactory.getLogger(InitialAdminSetupController.class);

    private final InitialAdminSetupService service;
    private final SetupTokenVerifier verifier;
    private final SetupAttemptLimiter limiter;

    public InitialAdminSetupController(InitialAdminSetupService service, SetupTokenVerifier verifier,
                                       SetupAttemptLimiter limiter) {
        this.service = service;
        this.verifier = verifier;
        this.limiter = limiter;
    }

    @GetMapping("/setup")
    public String form(Model model, HttpServletResponse response) {
        if (!service.isSetupOpen()) {
            return closed(response);
        }
        return render(model, new SetupForm(), null);
    }

    @PostMapping("/setup")
    public String submit(@ModelAttribute("form") SetupForm form, HttpServletRequest request,
                         HttpServletResponse response, Model model) {
        if (!service.isSetupOpen()) {
            return closed(response);
        }
        String address = request.getRemoteAddr();
        if (limiter.isBlocked(address)) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            model.addAttribute("throttled", true);
            return render(model, form.forRedisplay(), null);
        }
        String tokenError = switch (verifier.verify(form.getSetupToken())) {
            case NOT_CONFIGURED -> MSG_TOKEN_MISSING;
            case EMPTY -> MSG_TOKEN_EMPTY;
            case MISMATCH -> MSG_TOKEN_WRONG;
            case OK -> null;
        };
        if (tokenError != null) {
            limiter.recordFailure(address);
            LOG.warn("设置初始管理员口令校验失败");
            return render(model, form.forRedisplay(), Map.of("setupToken", tokenError));
        }
        try {
            service.setup(form);
        } catch (SetupClosedException e) {
            return closed(response);
        } catch (ValidationException e) {
            return render(model, form.forRedisplay(), e.getErrors());
        }
        return "redirect:/login?setup";
    }

    private String render(Model model, SetupForm form, Map<String, String> errors) {
        model.addAttribute("form", form);
        model.addAttribute("errors", errors);
        model.addAttribute("tokenMissing", !verifier.isConfigured());
        return "setup/form";
    }

    private static String closed(HttpServletResponse response) {
        LOG.info("设置初始管理员入口已关闭，请求被拒绝");
        response.setStatus(HttpStatus.FORBIDDEN.value());
        return "setup/closed";
    }
}
