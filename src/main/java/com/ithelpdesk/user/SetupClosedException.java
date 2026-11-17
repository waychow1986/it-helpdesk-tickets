package com.ithelpdesk.user;

/** 初始管理员已设置，设置流程不可用（FR-031）。 */
public class SetupClosedException extends RuntimeException {

    public SetupClosedException() {
        super("setup closed");
    }
}
