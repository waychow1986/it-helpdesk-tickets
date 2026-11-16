package com.ithelpdesk.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ithelpdesk.support.LogCapture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.TypeMismatchException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void accessDeniedRendersForbidden() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ModelAndView mav = handler.accessDenied(response);
        assertThat(mav.getViewName()).isEqualTo("error/403");
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(mav.getModel()).isEmpty();
    }

    @Test
    void badRequestRenders400() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(handler.badRequest(response).getViewName()).isEqualTo("error/400");
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void typeMismatchIsHandledAsBadRequestByAnnotation() throws Exception {
        org.springframework.web.bind.annotation.ExceptionHandler annotation = GlobalExceptionHandler.class
            .getMethod("badRequest", jakarta.servlet.http.HttpServletResponse.class)
            .getAnnotation(org.springframework.web.bind.annotation.ExceptionHandler.class);
        assertThat(annotation.value()).contains(BadRequestException.class, TypeMismatchException.class);
    }

    @Test
    void unexpectedErrorRenders500WithoutLoggingMessage() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        try (LogCapture logs = new LogCapture(GlobalExceptionHandler.class)) {
            ModelAndView mav = handler.unexpected(new IllegalStateException("客户内容：打印机卡纸"), response);
            assertThat(mav.getViewName()).isEqualTo("error/500");
            assertThat(response.getStatus()).isEqualTo(500);
            assertThat(logs.messages()).allMatch(m -> !m.contains("打印机"));
        }
    }

    @Test
    void missingResourceRenders404() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ModelAndView mav = handler.unexpected(new NoResourceFoundException(
            org.springframework.http.HttpMethod.GET, "/nope"), response);
        assertThat(mav.getViewName()).isEqualTo("error/404");
        assertThat(response.getStatus()).isEqualTo(404);
    }
}
