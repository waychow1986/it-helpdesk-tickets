package com.ithelpdesk.category;

import com.ithelpdesk.auth.CurrentUser;
import com.ithelpdesk.web.AccessDeniedException;
import com.ithelpdesk.web.ValidationException;
import java.time.Clock;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 类别维护（FR-005、FR-006）：新增、改名、移除（软删除）；有效类别名称唯一（INV-007）。 */
@Service
public class CategoryService {

    public static final int MAX_NAME = 50;

    private final CategoryRepository categories;
    private final Clock clock;

    public CategoryService(CategoryRepository categories, Clock clock) {
        this.categories = categories;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Category> listAll(CurrentUser actor) {
        requireAdmin(actor);
        return categories.findAll();
    }

    /** 当前有效类别（提交工单页使用）。 */
    @Transactional(readOnly = true)
    public List<Category> listActive() {
        return categories.findActive();
    }

    /** 全部类别（筛选选项使用）。 */
    @Transactional(readOnly = true)
    public List<Category> listAllForFilter() {
        return categories.findAll();
    }

    @Transactional
    public long create(CurrentUser actor, String rawName) {
        requireAdmin(actor);
        String name = validateName(rawName);
        if (categories.existsActiveName(name, null)) {
            throw new ValidationException("name", "类别名称已存在");
        }
        try {
            return categories.insert(name, clock.instant());
        } catch (DuplicateKeyException e) {
            throw new ValidationException("name", "类别名称已存在");
        }
    }

    @Transactional
    public void rename(CurrentUser actor, long id, String rawName) {
        requireAdmin(actor);
        String name = validateName(rawName);
        Category category = categories.findById(id)
            .orElseThrow(() -> new ValidationException("name", "类别不存在"));
        if (category.active() && categories.existsActiveName(name, id)) {
            throw new ValidationException("name", "类别名称已存在");
        }
        try {
            categories.rename(id, name);
        } catch (DuplicateKeyException e) {
            throw new ValidationException("name", "类别名称已存在");
        }
    }

    @Transactional
    public void remove(CurrentUser actor, long id) {
        requireAdmin(actor);
        if (categories.findById(id).isEmpty()) {
            throw new ValidationException("name", "类别不存在");
        }
        categories.remove(id, clock.instant());
    }

    /** 去除首尾空白后判空与长度（TD-013）。 */
    public static String validateName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            throw new ValidationException("name", "类别名称不能为空");
        }
        if (name.length() > MAX_NAME) {
            throw new ValidationException("name", "类别名称不能超过 " + MAX_NAME + " 个字符");
        }
        return name;
    }

    private static void requireAdmin(CurrentUser actor) {
        if (actor == null || !actor.isAdmin()) {
            throw new AccessDeniedException();
        }
    }
}
