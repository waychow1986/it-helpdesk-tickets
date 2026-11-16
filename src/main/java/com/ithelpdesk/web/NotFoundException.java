package com.ithelpdesk.web;

/** 资源不存在（仅对可查看全部数据的身份使用；员工访问不存在的工单按 TD-014 返回 403）。 */
public class NotFoundException extends RuntimeException {

    public NotFoundException() {
        super("not found");
    }
}
