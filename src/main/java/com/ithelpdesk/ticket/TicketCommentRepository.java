package com.ithelpdesk.ticket;

import com.ithelpdesk.user.Role;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 沟通记录数据访问：只有 INSERT 与 SELECT（INV-005）。 */
@Repository
public class TicketCommentRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public TicketCommentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long ticketId, long authorId, Role authorRole, String body, Instant now) {
        jdbc.update("INSERT INTO ticket_comment(ticket_id, author_id, author_role, body, created_at) "
                + "VALUES (:ticketId, :authorId, :role, :body, :now)",
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("authorId", authorId)
                .addValue("role", authorRole.name())
                .addValue("body", body)
                .addValue("now", Timestamp.from(now)));
    }

    public List<TicketComment> findByTicket(long ticketId) {
        return jdbc.query("SELECT c.id, c.ticket_id, c.author_id, u.display_name, c.author_role, c.body, c.created_at "
                + "FROM ticket_comment c JOIN app_user u ON u.id = c.author_id WHERE c.ticket_id = :ticketId "
                + "ORDER BY c.created_at, c.id",
            new MapSqlParameterSource("ticketId", ticketId),
            (rs, i) -> new TicketComment(rs.getLong("id"), rs.getLong("ticket_id"), rs.getLong("author_id"),
                rs.getString("display_name"), Role.valueOf(rs.getString("author_role")), rs.getString("body"),
                rs.getTimestamp("created_at").toInstant()));
    }
}
