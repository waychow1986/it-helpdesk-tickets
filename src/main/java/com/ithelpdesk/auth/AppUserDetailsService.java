package com.ithelpdesk.auth;

import com.ithelpdesk.user.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 按登录名精确查询账号；NULL 哈希的引导账号与已停用账号返回禁用用户（V1 FR-002、TD-011；V2 FR-009、TD-023）。
 * 禁用用户登录失败沿用 failureUrl("/login?error")，提示与凭据错误相同。
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public AppUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return users.findByLoginName(username == null ? "" : username)
            .map(CurrentUser::of)
            .orElseThrow(() -> new UsernameNotFoundException("bad credentials"));
    }
}
