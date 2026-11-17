package com.ithelpdesk.user;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** 创建账号表单（FR-001）。 */
public class UserForm {

    public static final int MAX_LOGIN = 64;
    public static final int MAX_PASSWORD_BYTES = 72;
    public static final int MAX_DISPLAY = 100;

    private String loginName;
    private String password;
    private String displayName;
    private String role;

    public UserForm() {
    }

    public UserForm(String loginName, String password, String displayName, String role) {
        this.loginName = loginName;
        this.password = password;
        this.displayName = displayName;
        this.role = role;
    }

    /** 服务端校验：去除首尾空白后判空、长度上限、身份码值（TD-013）。 */
    public Map<String, String> validate() {
        Map<String, String> errors = new LinkedHashMap<>();
        String login = trimmed(loginName);
        if (login.isEmpty()) {
            errors.put("loginName", "请填写登录名");
        } else if (login.length() > MAX_LOGIN) {
            errors.put("loginName", "登录名不能超过 " + MAX_LOGIN + " 个字符");
        }
        if (password == null || password.isBlank()) {
            errors.put("password", "请填写初始密码");
        } else if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            errors.put("password", "初始密码不能超过 " + MAX_PASSWORD_BYTES + " 个字节");
        }
        String display = trimmed(displayName);
        if (display.isEmpty()) {
            errors.put("displayName", "请填写显示姓名");
        } else if (display.length() > MAX_DISPLAY) {
            errors.put("displayName", "显示姓名不能超过 " + MAX_DISPLAY + " 个字符");
        }
        if (Role.parse(role) == null) {
            errors.put("role", "请选择身份");
        }
        return errors;
    }

    /** 校验失败回填用副本：不回显密码。 */
    public UserForm forRedisplay() {
        return new UserForm(loginName, null, displayName, role);
    }

    static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    public String getLoginName() {
        return loginName;
    }

    public void setLoginName(String loginName) {
        this.loginName = loginName;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    @Override
    public String toString() {
        return "UserForm[role=" + role + "]";
    }
}
