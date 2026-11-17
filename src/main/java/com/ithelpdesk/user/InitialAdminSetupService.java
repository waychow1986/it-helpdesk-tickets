package com.ithelpdesk.user;

import com.ithelpdesk.web.ValidationException;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 一次性设置初始管理员（FR-031、TD-011、TD-017）：开放判定 = 所连数据库中不存在带凭据的 ADMIN，每次实时查询；
 * 设置在单个事务中以 advisory lock 串行化并再次判定，只能新建 ADMIN 账号，不修改任何既有行。
 * 日志只记录用户 id，不记录登录名、姓名、密码或哈希。
 */
@Service
public class InitialAdminSetupService {

    private static final Logger LOG = LoggerFactory.getLogger(InitialAdminSetupService.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public InitialAdminSetupService(UserRepository users, PasswordEncoder passwordEncoder, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public boolean isSetupOpen() {
        return !users.existsCredentialedAdmin();
    }

    /**
     * 创建初始管理员；流程已关闭时抛出 {@link SetupClosedException}，校验失败抛出 {@link ValidationException}，
     * 两者都不写入任何数据。调用前须已通过设置口令校验（TD-019，由控制器完成）。
     */
    @Transactional
    public long setup(SetupForm form) {
        users.acquireSetupLock();
        if (users.existsCredentialedAdmin()) {
            throw new SetupClosedException();
        }
        Map<String, String> errors = form.validate();
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        String login = form.getLoginName().trim();
        if (users.findByLoginName(login).isPresent()) {
            throw new ValidationException("loginName", "登录名已存在");
        }
        long id;
        try {
            id = users.insertInitialAdmin(login, passwordEncoder.encode(form.getPassword()),
                form.getDisplayName().trim(), clock.instant());
        } catch (DuplicateKeyException e) {
            throw new ValidationException("loginName", "登录名已存在");
        }
        LOG.info("初始管理员设置完成（用户 id={}）", id);
        return id;
    }
}
