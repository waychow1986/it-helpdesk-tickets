package com.ithelpdesk.web;

/** 请求参数无法解析，渲染 400"请求参数无效"。 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String reason) {
        super(reason);
    }
}
