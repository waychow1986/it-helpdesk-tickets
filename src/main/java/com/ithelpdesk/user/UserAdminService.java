package com.ithelpdesk.user;

import com.ithelpdesk.auth.CurrentUser;
import com.ithelpdesk.web.AccessDeniedException;
import com.ithelpdesk.web.ValidationException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 账号管理：仅管理员可创建账号与切换 IT 同事身份（FR-001、FR-004）。 */
@Service
public class UserAdminService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public UserAdminService(UserRepository users, PasswordEncoder passwordEncoder, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AppUser> listUsers(CurrentUser actor) {
        requireAdmin(actor);
        return users.findAll();
    }

    @Transactional
    public long createUser(CurrentUser actor, UserForm form) {
        requireAdmin(actor);
        Map<String, String> errors = form.validate();
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        String login = form.getLoginName().trim();
        if (users.findByLoginName(login).isPresent()) {
            throw new ValidationException("loginName", "登录名已存在");
        }
        try {
            return users.insert(login, passwordEncoder.encode(form.getPassword()), form.getDisplayName().trim(),
                Role.parse(form.getRole()), clock.instant(), actor.getId());
        } catch (DuplicateKeyException e) {
            throw new ValidationException("loginName", "登录名已存在");
        }
    }

    /** 仅允许 EMPLOYEE ↔ IT_STAFF；管理员账号身份不可在页面修改。 */
    @Transactional
    public void changeRole(CurrentUser actor, long userId, String roleCode) {
        requireAdmin(actor);
        Role target = Role.parse(roleCode);
        if (target != Role.EMPLOYEE && target != Role.IT_STAFF) {
            throw new ValidationException("role", "只能在员工与 IT 同事之间切换身份");
        }
        AppUser user = users.findById(userId).orElseThrow(() -> new ValidationException("role", "账号不存在"));
        if (user.role() == Role.ADMIN) {
            throw new ValidationException("role", "管理员账号的身份不能修改");
        }
        if (users.updateStaffRole(userId, target) == 0) {
            throw new ValidationException("role", "管理员账号的身份不能修改");
        }
    }

    static void requireAdmin(CurrentUser actor) {
        if (actor == null || !actor.isAdmin()) {
            throw new AccessDeniedException();
        }
    }
}
