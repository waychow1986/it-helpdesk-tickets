package com.ithelpdesk.ticket;

import com.ithelpdesk.auth.CurrentUser;
import com.ithelpdesk.user.AppUser;
import com.ithelpdesk.user.UserRepository;
import com.ithelpdesk.web.AccessDeniedException;
import com.ithelpdesk.web.NotFoundException;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工单查询与可见范围（FR-010、FR-011、FR-013、FR-014、FR-024、INV-003）：
 * 员工只见本人提交的工单；IT 同事可见全部；管理员不查看工单（A-004）。
 */
@Service
public class TicketQueryService {

    public static final int PAGE_SIZE = 50;

    private final TicketRepository tickets;
    private final TicketCommentRepository comments;
    private final TicketEventRepository events;
    private final UserRepository users;

    public TicketQueryService(TicketRepository tickets, TicketCommentRepository comments,
                              TicketEventRepository events, UserRepository users) {
        this.tickets = tickets;
        this.comments = comments;
        this.events = events;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public TicketPage search(CurrentUser actor, TicketSearchCriteria criteria) {
        Long scope = visibilityScope(actor);
        if (criteria.getDateRangeError() != null) {
            return new TicketPage(List.of(), criteria.getPage(), false);
        }
        int offset = (criteria.getPage() - 1) * PAGE_SIZE;
        List<Ticket> rows = tickets.search(criteria, scope, PAGE_SIZE + 1, offset);
        boolean hasNext = rows.size() > PAGE_SIZE;
        return new TicketPage(hasNext ? rows.subList(0, PAGE_SIZE) : rows, criteria.getPage(), hasNext);
    }

    @Transactional(readOnly = true)
    public TicketDetail detail(CurrentUser actor, long ticketId) {
        Ticket ticket = requireVisible(actor, ticketId);
        return new TicketDetail(ticket, comments.findByTicket(ticketId), events.findByTicket(ticketId));
    }

    /** 分派对象：启用状态的 IT 同事中除本人以外的账号（V1 FR-016；V2 FR-011、TD-026）。 */
    @Transactional(readOnly = true)
    public List<AppUser> assignableStaff(CurrentUser actor) {
        if (actor == null || !actor.isItStaff()) {
            return List.of();
        }
        return users.findAssignableItStaff().stream().filter(u -> u.id() != actor.getId()).toList();
    }

    /** 筛选“处理人”选项：IT 同事 ∪ 曾任处理人，不按启用状态过滤（FR-013、TD-026）。 */
    @Transactional(readOnly = true)
    public List<TicketRepository.UserOption> assigneeOptions(CurrentUser actor) {
        visibilityScope(actor);
        return tickets.findAssigneeOptions();
    }

    /** 员工 → 本人 id；IT 同事 → null（不限）；其他身份拒绝。 */
    static Long visibilityScope(CurrentUser actor) {
        if (actor == null) {
            throw new AccessDeniedException();
        }
        if (actor.isEmployee()) {
            return actor.getId();
        }
        if (actor.isItStaff()) {
            return null;
        }
        throw new AccessDeniedException();
    }

    /** 读取可见工单；员工访问他人或不存在的工单统一拒绝（TD-014）。 */
    Ticket requireVisible(CurrentUser actor, long ticketId) {
        Long scope = visibilityScope(actor);
        Optional<Ticket> ticket = tickets.findById(ticketId);
        if (scope != null) {
            if (ticket.isEmpty() || ticket.get().submitterId() != scope) {
                throw new AccessDeniedException();
            }
            return ticket.get();
        }
        return ticket.orElseThrow(NotFoundException::new);
    }
}
