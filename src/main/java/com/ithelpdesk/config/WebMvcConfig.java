package com.ithelpdesk.config;

import com.ithelpdesk.auth.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** 为所有页面提供当前用户（用于按身份渲染导航，FR-003）。 */
@ControllerAdvice
public class WebMvcConfig {

    @ModelAttribute("currentUser")
    public CurrentUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CurrentUser user) {
            return user;
        }
        return null;
    }
}
