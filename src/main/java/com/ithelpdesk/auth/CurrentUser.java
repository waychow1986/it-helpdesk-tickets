package com.ithelpdesk.auth;

import com.ithelpdesk.user.AppUser;
import com.ithelpdesk.user.Role;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 已登录用户（会话 principal）：id / 登录名 / 姓名 / 身份；认证完成后擦除密码哈希。
 * isEnabled = 已设置凭据且账号处于启用状态（TD-011、TD-023）。
 */
public final class CurrentUser implements UserDetails, CredentialsContainer {

    private static final long serialVersionUID = 1L;

    private final long id;
    private final String loginName;
    private final String displayName;
    private final Role role;
    private final boolean enabled;
    private String passwordHash;

    public CurrentUser(long id, String loginName, String displayName, Role role, String passwordHash) {
        this(id, loginName, displayName, role, passwordHash, true);
    }

    public CurrentUser(long id, String loginName, String displayName, Role role, String passwordHash,
                       boolean active) {
        this.id = id;
        this.loginName = loginName;
        this.displayName = displayName;
        this.role = role;
        this.passwordHash = passwordHash;
        this.enabled = passwordHash != null && active;
    }

    private CurrentUser(long id, String loginName, String displayName, Role role) {
        this.id = id;
        this.loginName = loginName;
        this.displayName = displayName;
        this.role = role;
        this.passwordHash = "";
        this.enabled = true;
    }

    public static CurrentUser of(AppUser user) {
        return new CurrentUser(user.id(), user.loginName(), user.displayName(), user.role(), user.passwordHash(),
            user.active());
    }

    /** 以新身份重建（RoleRefreshFilter 使用，仅针对启用账号），不携带凭据。 */
    public CurrentUser withRole(Role newRole) {
        return new CurrentUser(id, loginName, displayName, newRole);
    }

    public long getId() {
        return id;
    }

    public String getLoginName() {
        return loginName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Role getRole() {
        return role;
    }

    public boolean isEmployee() {
        return role == Role.EMPLOYEE;
    }

    public boolean isItStaff() {
        return role == Role.IT_STAFF;
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities(role);
    }

    public static List<GrantedAuthority> authorities(Role role) {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash == null ? "" : passwordHash;
    }

    @Override
    public String getUsername() {
        return loginName;
    }

    /** password_hash 为 NULL 的引导账号与已停用账号视为禁用（TD-011、TD-023）。 */
    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void eraseCredentials() {
        passwordHash = "";
    }

    @Override
    public String toString() {
        return "CurrentUser[id=" + id + ", role=" + role + "]";
    }
}
