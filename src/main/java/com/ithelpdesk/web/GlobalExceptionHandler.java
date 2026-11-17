package com.ithelpdesk.web;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

/** 统一错误页：403 / 400 / 404 / 500；不在页面或日志中输出任何业务内容。 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AccessDeniedException.class)
    public ModelAndView accessDenied(HttpServletResponse response) {
        return view("error/403", HttpStatus.FORBIDDEN, response);
    }

    @ExceptionHandler({BadRequestException.class, TypeMismatchException.class,
        MissingServletRequestParameterException.class})
    public ModelAndView badRequest(HttpServletResponse response) {
        return view("error/400", HttpStatus.BAD_REQUEST, response);
    }

    @ExceptionHandler(NotFoundException.class)
    public ModelAndView notFound(HttpServletResponse response) {
        return view("error/404", HttpStatus.NOT_FOUND, response);
    }

    @ExceptionHandler(Exception.class)
    public ModelAndView unexpected(Exception ex, HttpServletResponse response) {
        if (ex instanceof ErrorResponse errorResponse) {
            int status = errorResponse.getStatusCode().value();
            if (status == HttpStatus.NOT_FOUND.value()) {
                return view("error/404", HttpStatus.NOT_FOUND, response);
            }
            if (status >= 400 && status < 500) {
                return view("error/400", HttpStatus.valueOf(status), response);
            }
        }
        // 只记录异常类型，不记录消息（可能包含用户输入或客户内容）。
        LOG.error("unexpected error: {}", ex.getClass().getName());
        return view("error/500", HttpStatus.INTERNAL_SERVER_ERROR, response);
    }

    private static ModelAndView view(String name, HttpStatus status, HttpServletResponse response) {
        response.setStatus(status.value());
        ModelAndView mav = new ModelAndView(name);
        mav.setStatus(status);
        return mav;
    }
}
