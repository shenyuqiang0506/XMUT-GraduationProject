package org.shen.identity.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.shen.platform.contracts.AuthActor;
import org.shen.platform.support.BusinessException;
import org.slf4j.MDC;
import org.shen.platform.contracts.TraceIds;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@Profile("business & !m1")
public class IdentityRepository {
    public record User(long id, String username, String passwordHash, String displayName, String status, long version) {
    }

    public record Session(long id, long userId, String deviceId, String refreshHash, Instant expiresAt,
                          boolean revoked) {
    }

    private final JdbcTemplate sql;

    public IdentityRepository(JdbcTemplate sql) {
        this.sql = sql;
    }

    private static final RowMapper<User> USER = (rs, n) -> new User(rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"), rs.getString("display_name"), rs.getString("status"), rs.getLong("auth_version"));
    private static final RowMapper<Session> SESSION = (rs, n) -> new Session(rs.getLong("id"), rs.getLong("user_id"), rs.getString("device_id"), rs.getString("refresh_token_hash"), rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("revoked_at") != null);

    public JdbcTemplate sql() {
        return sql;
    }

    public User username(String name, boolean lock) {
        return sql.query("SELECT * FROM `user` WHERE username=?" + (lock ? " FOR UPDATE" : ""), USER, name).stream().findFirst().orElse(null);
    }

    public User user(long id, boolean lock) {
        return sql.query("SELECT * FROM `user` WHERE id=?" + (lock ? " FOR UPDATE" : ""), USER, id).stream().findFirst().orElse(null);
    }

    public List<String> roles(long id) {
        return sql.queryForList("SELECT r.code FROM `role` r JOIN user_role ur ON ur.role_id=r.id WHERE ur.user_id=? ORDER BY r.code", String.class, id);
    }

    public AuthActor actor(User u, String sid) {
        return new AuthActor(Long.toString(u.id()), sid, u.username(), u.displayName(), u.status(), u.version(), roles(u.id()));
    }

    public Session session(long id, boolean lock) {
        return sql.query("SELECT * FROM user_session WHERE id=?" + (lock ? " FOR UPDATE" : ""), SESSION, id).stream().findFirst().orElse(null);
    }

    public Session findRefresh(String hash) {
        var current = sql.query("SELECT * FROM user_session WHERE refresh_token_hash=?", SESSION, hash);
        if (!current.isEmpty()) return current.getFirst();
        return sql.query("SELECT s.* FROM used_refresh_token t JOIN user_session s ON s.id=t.session_id WHERE t.token_hash=?", SESSION, hash).stream().findFirst().orElse(null);
    }

    public long createUser(String username, String hash, String name, Set<String> roles) {
        sql.update("INSERT INTO `user`(username,password_hash,display_name,status,auth_version) VALUES (?,?,?,'ACTIVE',0)", username, hash, name);
        long id = sql.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        setRoles(id, roles);
        return id;
    }

    public void setRoles(long id, Set<String> roles) {
        sql.update("DELETE FROM user_role WHERE user_id=?", id);
        for (String role : roles) {
            int count = sql.update("INSERT INTO user_role(user_id,role_id) SELECT ?,id FROM `role` WHERE code=?", id, role);
            if (count != 1) throw new BusinessException(400, "INVALID_ROLE", "角色不存在");
        }
    }

    public void audit(Long actor, String action, long object, String from, String to) {
        sql.update("INSERT INTO audit_log(actor_id,action,object_type,object_id,from_status,to_status,trace_id) VALUES (?,?,'USER',?,?,?,?)",
                actor, action, object, from, to, TraceIds.acceptOrCreate(MDC.get(TraceIds.MDC_KEY)));
    }
}
