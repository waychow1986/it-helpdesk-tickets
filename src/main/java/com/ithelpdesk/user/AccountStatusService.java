package com.ithelpdesk.user;

import com.ithelpdesk.auth.CurrentUser;
import com.ithelpdesk.web.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号停用 / 重新启用（TD-025，FR-007、FR-014、FR-015、INV-004、INV-005）。
 * 单个事务内：advisory lock 串行化 → 目标行 FOR UPDATE → 不存在 / 已是期望状态处理 →
 * 停用可用管理员时确认仍有其他可用管理员 → 条件更新 active。只改变 active 列，不写处理记录、不改任何工单。
 * 账号启用状态只由本服务写入。
 */
@Service
public class AccountStatusService {

    public static final String MSG_NOT_FOUND = "账号不存在";
    public static final String MSG_LAST_ADMIN = "系统必须至少保留一个可用的管理员";
    public static final String MSG_DEACTIVATED = "已停用该账号";
    public static final String MSG_ACTIVATED = "已启用该账号";
    public static final String MSG_ALREADY_INACTIVE = "账号已是停用状态";
    public static final String MSG_ALREADY_ACTIVE = "账号已是启用状态";

    private static final Logger LOG = LoggerFactory.getLogger(AccountStatusService.class);

    private final UserRepository users;

    public AccountStatusService(UserRepository users) {
        this.users = users;
    }

    /** 操作结果：changed 表示状态已改变；message 为页面提示。 */
    public record Result(boolean changed, String message) {
    }

    @Transactional
    public Result deactivate(CurrentUser actor, long targetId) {
        return change(actor, targetId, false);
    }

    @Transactional
    public Result activate(CurrentUser actor, long targetId) {
        return change(actor, targetId, true);
    }

    private Result change(CurrentUser actor, long targetId, boolean activate) {
        UserAdminService.requireAdmin(actor);
        users.acquireAccountStatusLock();
        AppUser target = users.lockForStatusChange(targetId)
            .orElseThrow(() -> new ValidationException("account", MSG_NOT_FOUND));
        if (target.active() == activate) {
            return new Result(false, activate ? MSG_ALREADY_ACTIVE : MSG_ALREADY_INACTIVE);
        }
        if (!activate && isUsableAdmin(target) && users.countOtherUsableAdmins(targetId) == 0) {
            throw new ValidationException("account", MSG_LAST_ADMIN);
        }
        if (users.updateActive(targetId, activate, !activate) != 1) {
            throw new IllegalStateException("account status update affected unexpected rows");
        }
        LOG.info("{}（用户 id={}，操作者 id={}）", activate ? "账号已启用" : "账号已停用", targetId, actor.getId());
        return new Result(true, activate ? MSG_ACTIVATED : MSG_DEACTIVATED);
    }

    /** 可用管理员：身份为管理员、启用、已设置凭据（遗留的 NULL 哈希 ADMIN 不计入）。 */
    static boolean isUsableAdmin(AppUser user) {
        return user.role() == Role.ADMIN && user.active() && user.passwordHash() != null;
    }
}
