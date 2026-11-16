package com.ithelpdesk.ticket;

import com.ithelpdesk.web.ValidationException;

/** 补充说明 / 回复表单（FR-012、FR-018）。 */
public class CommentForm {

    private String body;

    public CommentForm() {
    }

    public CommentForm(String body) {
        this.body = body;
    }

    /** 去除首尾空白后判空与长度；返回去空白后的内容。 */
    public static String validateBody(String body) {
        String b = body == null ? "" : body.trim();
        if (b.isEmpty()) {
            throw new ValidationException("body", "内容不能为空");
        }
        if (b.length() > TicketForm.MAX_TEXT) {
            throw new ValidationException("body", "内容不能超过 " + TicketForm.MAX_TEXT + " 个字符");
        }
        return b;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }
}
