package com.ithelpdesk.ticket;

import com.ithelpdesk.auth.CurrentUser;
import com.ithelpdesk.category.Category;
import com.ithelpdesk.category.CategoryRepository;
import com.ithelpdesk.user.Role;
import com.ithelpdesk.user.UserRepository;
import com.ithelpdesk.web.AccessDeniedException;
import com.ithelpdesk.web.BadRequestException;
import com.ithelpdesk.web.NotFoundException;
import com.ithelpdesk.web.ValidationException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工单写操作（服务层授权 + 校验 + 事务）：提交、补充说明 / 回复、认领、分派、改状态。
 * 认领 / 分派 / 改状态以工单行锁串行化，任一失败整体回滚（design「关键流程」3～5）。
 */
@Service
public class TicketCommandService {

    public static final String MSG_ASSIGNEE_NOT_STAFF = "分派对象必须是其他 IT 同事";
    public static final String MSG_ASSIGNEE_INACTIVE = "该 IT 同事已停用，不能分派，请刷新后重新选择";

    private static final Logger LOG = LoggerFactory.getLogger(TicketCommandService.class);

    private final TicketRepository tickets;
    private final TicketCommentRepository comments;
    private final TicketEventRepository events;
    private final CategoryRepository categories;
    private final UserRepository users;
    private final Clock clock;

    public TicketCommandService(TicketRepository tickets, TicketCommentRepository comments,
                                TicketEventRepository events, CategoryRepository categories, UserRepository users,
                                Clock clock) {
        this.tickets = tickets;
        this.comments = comments;
        this.events = events;
        this.categories = categories;
        this.users = users;
        this.clock = clock;
    }

    /** 员工提交工单（FR-007、FR-008）。 */
    @Transactional
    public long submit(CurrentUser actor, TicketForm form) {
        if (actor == null || !actor.isEmployee()) {
            throw new AccessDeniedException();
        }
        Map<String, String> errors = form.validate();
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        Optional<Category> category = categories.findByIdForShare(form.parsedCategoryId());
        if (category.isEmpty() || !category.get().active()) {
            throw new ValidationException("categoryId", "所选类别已不可用");
        }
        return tickets.insert(form.getTitle().trim(), form.getDescription().trim(), category.get().id(),
            Urgency.parse(form.getUrgency()), actor.getId(), clock.instant());
    }

    /** 员工补充说明（仅本人工单）或 IT 同事回复（任意工单）；不改变状态与处理人（FR-012、FR-018）。 */
    @Transactional
    public void addComment(CurrentUser actor, long ticketId, String body) {
        if (actor == null || !(actor.isEmployee() || actor.isItStaff())) {
            throw new AccessDeniedException();
        }
        Optional<Long> submitter = tickets.findSubmitterId(ticketId);
        if (actor.isEmployee() && (submitter.isEmpty() || submitter.get() != actor.getId())) {
            throw new AccessDeniedException();
        }
        if (submitter.isEmpty()) {
            throw new NotFoundException();
        }
        String content = CommentForm.validateBody(body);
        comments.insert(ticketId, actor.getId(), actor.getRole(), content, clock.instant());
    }

    /** 认领：处理人改为本人（FR-015）。返回提示文本。 */
    @Transactional
    public String claim(CurrentUser actor, long ticketId) {
        requireItStaff(actor);
        TicketRepository.TicketState state = tickets.lockForUpdate(ticketId).orElseThrow(NotFoundException::new);
        if (Objects.equals(state.assigneeId(), actor.getId())) {
            return "您已是该工单处理人";
        }
        Instant now = clock.instant();
        tickets.updateAssignee(ticketId, actor.getId(), now);
        events.insert(ticketId, actor.getId(), EventType.CLAIM, state.assigneeId(), actor.getId(), null, null, now);
        return "已认领该工单";
    }

    /**
     * 分派给另一位启用状态的 IT 同事（V1 FR-016、INV-002；V2 FR-011、TD-026）。返回提示文本。
     * 工单行锁之后以 FOR SHARE 读取目标身份与启用状态，与停用的 UPDATE 互斥，按提交顺序生效。
     */
    @Transactional
    public String assign(CurrentUser actor, long ticketId, String assigneeIdValue) {
        requireItStaff(actor);
        Long targetId = parseId(assigneeIdValue);
        if (targetId == null) {
            throw new ValidationException("assigneeId", "请选择分派对象");
        }
        TicketRepository.TicketState state = tickets.lockForUpdate(ticketId).orElseThrow(NotFoundException::new);
        Optional<UserRepository.AssigneeCandidate> target = targetId == actor.getId() ? Optional.empty()
            : users.findAssigneeCandidateForShare(targetId);
        if (target.isEmpty() || target.get().role() != Role.IT_STAFF) {
            throw new ValidationException("assigneeId", MSG_ASSIGNEE_NOT_STAFF);
        }
        if (!target.get().active()) {
            LOG.info("分派被拒绝：目标账号已停用（工单 id={}，目标 id={}）", ticketId, targetId);
            throw new ValidationException("assigneeId", MSG_ASSIGNEE_INACTIVE);
        }
        if (Objects.equals(state.assigneeId(), targetId)) {
            return "处理人未变化";
        }
        Instant now = clock.instant();
        tickets.updateAssignee(ticketId, targetId, now);
        events.insert(ticketId, actor.getId(), EventType.ASSIGN, state.assigneeId(), targetId, null, null, now);
        return "已分派该工单";
    }

    /** 修改状态（FR-017）；首次改为已解决时记录首次已解决时刻与当时处理人（TD-008）。 */
    @Transactional
    public String changeStatus(CurrentUser actor, long ticketId, String statusCode) {
        requireItStaff(actor);
        TicketStatus target = TicketStatus.parse(statusCode);
        if (target == null) {
            throw new BadRequestException("status");
        }
        TicketRepository.TicketState state = tickets.lockForUpdate(ticketId).orElseThrow(NotFoundException::new);
        if (state.status() == target) {
            return "状态未变化";
        }
        Instant now = clock.instant();
        tickets.updateStatus(ticketId, target, now);
        events.insert(ticketId, actor.getId(), EventType.STATUS, null, null, state.status(), target, now);
        return "状态已更新";
    }

    private static void requireItStaff(CurrentUser actor) {
        if (actor == null || !actor.isItStaff()) {
            throw new AccessDeniedException();
        }
    }

    private static Long parseId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long id = Long.parseLong(value.trim());
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
