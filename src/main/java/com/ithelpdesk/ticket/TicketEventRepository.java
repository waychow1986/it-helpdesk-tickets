package com.ithelpdesk.ticket;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 处理记录数据访问：只有 INSERT 与 SELECT（FR-020、INV-005）。 */
@Repository
public class TicketEventRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public TicketEventRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long ticketId, long actorId, EventType type, Long fromAssigneeId, Long toAssigneeId,
                       TicketStatus fromStatus, TicketStatus toStatus, Instant now) {
        jdbc.update("INSERT INTO ticket_event(ticket_id, actor_id, event_type, from_assignee_id, to_assignee_id, "
                + "from_status, to_status, created_at) VALUES (:ticketId, :actorId, :type, :fromAssignee, :toAssignee, "
                + ":fromStatus, :toStatus, :now)",
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("actorId", actorId)
                .addValue("type", type.name())
                .addValue("fromAssignee", fromAssigneeId, java.sql.Types.BIGINT)
                .addValue("toAssignee", toAssigneeId, java.sql.Types.BIGINT)
                .addValue("fromStatus", fromStatus == null ? null : fromStatus.name(), java.sql.Types.VARCHAR)
                .addValue("toStatus", toStatus == null ? null : toStatus.name(), java.sql.Types.VARCHAR)
                .addValue("now", Timestamp.from(now)));
    }

    public List<TicketEvent> findByTicket(long ticketId) {
        return jdbc.query("SELECT e.id, e.ticket_id, e.actor_id, u.display_name AS actor_name, e.event_type, "
                + "fa.display_name AS from_name, ta.display_name AS to_name, e.from_status, e.to_status, e.created_at "
                + "FROM ticket_event e JOIN app_user u ON u.id = e.actor_id "
                + "LEFT JOIN app_user fa ON fa.id = e.from_assignee_id "
                + "LEFT JOIN app_user ta ON ta.id = e.to_assignee_id "
                + "WHERE e.ticket_id = :ticketId ORDER BY e.created_at, e.id",
            new MapSqlParameterSource("ticketId", ticketId),
            (rs, i) -> new TicketEvent(rs.getLong("id"), rs.getLong("ticket_id"), rs.getLong("actor_id"),
                rs.getString("actor_name"), EventType.valueOf(rs.getString("event_type")), rs.getString("from_name"),
                rs.getString("to_name"), rs.getString("from_status"), rs.getString("to_status"),
                rs.getTimestamp("created_at").toInstant()));
    }
}
