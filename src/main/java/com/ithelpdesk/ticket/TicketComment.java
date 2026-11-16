package com.ithelpdesk.ticket;

import com.ithelpdesk.user.Role;
import java.time.Instant;

/** 沟通记录：员工补充说明或 IT 同事回复（只追加，INV-005）。 */
public record TicketComment(long id, long ticketId, long authorId, String authorName, Role authorRole, String body,
                            Instant createdAt) {
}
