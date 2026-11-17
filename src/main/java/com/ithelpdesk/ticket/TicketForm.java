package com.ithelpdesk.ticket;

import java.util.LinkedHashMap;
import java.util.Map;

/** 提交工单表单（FR-007）：字段以字符串绑定，全部校验在服务端完成（TD-013）。 */
public class TicketForm {

    public static final int MAX_TITLE = 200;
    public static final int MAX_TEXT = 10000;

    private String title;
    private String description;
    private String categoryId;
    private String urgency;

    public TicketForm() {
    }

    public TicketForm(String title, String description, String categoryId, String urgency) {
        this.title = title;
        this.description = description;
        this.categoryId = categoryId;
        this.urgency = urgency;
    }

    /** 逐项校验，返回字段名 → 提示（为空表示通过）。 */
    public Map<String, String> validate() {
        Map<String, String> errors = new LinkedHashMap<>();
        String t = trimmed(title);
        if (t.isEmpty()) {
            errors.put("title", "请填写标题");
        } else if (t.length() > MAX_TITLE) {
            errors.put("title", "标题不能超过 " + MAX_TITLE + " 个字符");
        }
        String d = trimmed(description);
        if (d.isEmpty()) {
            errors.put("description", "请填写问题描述");
        } else if (d.length() > MAX_TEXT) {
            errors.put("description", "问题描述不能超过 " + MAX_TEXT + " 个字符");
        }
        if (parsedCategoryId() == null) {
            errors.put("categoryId", "请选择类别");
        }
        if (Urgency.parse(urgency) == null) {
            errors.put("urgency", "请选择紧急程度");
        }
        return errors;
    }

    public Long parsedCategoryId() {
        String c = trimmed(categoryId);
        if (c.isEmpty()) {
            return null;
        }
        try {
            long id = Long.parseLong(c);
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(String categoryId) {
        this.categoryId = categoryId;
    }

    public String getUrgency() {
        return urgency;
    }

    public void setUrgency(String urgency) {
        this.urgency = urgency;
    }
}
