package org.shen.identity.infrastructure;

import lombok.RequiredArgsConstructor;

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

/**
 * 身份库的 JDBC 访问层，集中处理账号、角色、设备会话和审计记录。
 * 带 lock 的查询可追加 FOR UPDATE，调用方必须在事务中使用才能保持行锁。
 * 本层按需组合 SQL；业务权限、事务范围与并发顺序由应用服务决定。
 */
@Repository
@Profile("business")
@RequiredArgsConstructor
public class IdentityRepository {
    public record User(long id, String username, String passwordHash, String displayName, String status, long version) {
    }

    public record Session(long id, long userId, String deviceId, String refreshHash, Instant expiresAt,
                          boolean revoked) {
    }

    private final JdbcTemplate sql;

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

    /**
     * 先查当前刷新摘要，再查已使用的历史摘要；历史命中用于识别重放，不能直接签发新令牌。
     */
    public Session findRefresh(String hash) {
        var current = sql.query("SELECT * FROM user_session WHERE refresh_token_hash=?", SESSION, hash);
        if (!current.isEmpty()) return current.getFirst();
        return sql.query("SELECT s.* FROM used_refresh_token t JOIN user_session s ON s.id=t.session_id WHERE t.token_hash=?", SESSION, hash).stream().findFirst().orElse(null);
    }

    /**
     * 在调用方事务内创建账号及角色。LAST_INSERT_ID 依赖同一 JDBC 事务连接，不能拆成异步查询。
     */
    public long createUser(String username, String hash, String name, Set<String> roles) {
        sql.update("INSERT INTO `user`(username,password_hash,display_name,status,auth_version) VALUES (?,?,?,'ACTIVE',0)", username, hash, name);
        long id = sql.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        setRoles(id, roles);
        return id;
    }

    /**
     * 整体替换角色集合；任一角色不存在即抛错，由外层事务回滚删除和已插入记录。
     */
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
