package com.ithelpdesk.user;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** 设置初始管理员表单（FR-031、TD-011、TD-019）；校验规则与创建账号相同（TD-013）。 */
public class SetupForm {

    private String setupToken;
    private String loginName;
    private String password;
    private String displayName;

    public SetupForm() {
    }

    public SetupForm(String setupToken, String loginName, String password, String displayName) {
        this.setupToken = setupToken;
        this.loginName = loginName;
        this.password = password;
        this.displayName = displayName;
    }

    /** 服务端校验：去除首尾空白后判空、长度上限（不含设置口令，口令由 SetupTokenVerifier 校验）。 */
    public Map<String, String> validate() {
        Map<String, String> errors = new LinkedHashMap<>();
        String login = trimmed(loginName);
        if (login.isEmpty()) {
            errors.put("loginName", "请填写登录名");
        } else if (login.length() > UserForm.MAX_LOGIN) {
            errors.put("loginName", "登录名不能超过 " + UserForm.MAX_LOGIN + " 个字符");
        }
        if (password == null || password.isBlank()) {
            errors.put("password", "请填写密码");
        } else if (password.getBytes(StandardCharsets.UTF_8).length > UserForm.MAX_PASSWORD_BYTES) {
            errors.put("password", "密码不能超过 " + UserForm.MAX_PASSWORD_BYTES + " 个字节");
        }
        String display = trimmed(displayName);
        if (display.isEmpty()) {
            errors.put("displayName", "请填写姓名");
        } else if (display.length() > UserForm.MAX_DISPLAY) {
            errors.put("displayName", "姓名不能超过 " + UserForm.MAX_DISPLAY + " 个字符");
        }
        return errors;
    }

    /** 重渲染用副本：回填登录名与姓名，清空密码与设置口令。 */
    public SetupForm forRedisplay() {
        return new SetupForm(null, loginName, null, displayName);
    }

    static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    public String getSetupToken() {
        return setupToken;
    }

    public void setSetupToken(String setupToken) {
        this.setupToken = setupToken;
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

    @Override
    public String toString() {
        return "SetupForm[***]";
    }
}
