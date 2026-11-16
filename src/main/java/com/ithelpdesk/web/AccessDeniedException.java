package com.ithelpdesk.web;

/** 服务层越权拒绝，统一渲染 403 页面且不含业务数据（TD-014）。 */
public class AccessDeniedException extends RuntimeException {

    public AccessDeniedException() {
        super("access denied");
    }
}
