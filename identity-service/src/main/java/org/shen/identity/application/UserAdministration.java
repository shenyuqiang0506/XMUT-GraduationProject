package org.shen.identity.application;

import lombok.RequiredArgsConstructor;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import org.shen.identity.domain.AuthCommands;
import org.shen.identity.domain.PersonnelAccounts;
import org.shen.identity.infrastructure.*;
import org.shen.platform.contracts.AuthActor;
import org.shen.platform.support.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 系统管理员的账号管理用例。
 * 权限在写事务内重新核验，幂等记录与实际修改共同提交，并保护最后一个有效管理员。
 * 状态或角色变化会递增身份版本并撤销全部旧会话，使旧 JWT 不能继续使用。
 */
@Service
@Profile("business")
@RequiredArgsConstructor
public class UserAdministration {
    public record Mutation(int status, Object data, boolean replayed) {
    }

    private final IdentityRepository repo;
    private final PasswordEncoder passwords;
    private final RevocationCache cache;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final AccessTokens tokens;

    public Map<String, Object> view(IdentityRepository.User user) {
        return Map.of("id", Long.toString(user.id()), "username", user.username(), "displayName", user.displayName(),
                "status", user.status(), "authVersion", user.version(), "roles", repo.roles(user.id()));
    }

    private void requireAdmin(AuthActor actor) {
        var current = repo.user(Long.parseLong(actor.userId()), false);
        if (current == null || !"ACTIVE".equals(current.status()) || current.version() != actor.authVersion()
                || !repo.roles(current.id()).contains("SYSTEM_ADMIN"))
            throw new BusinessException(403, "FORBIDDEN", "需要有效的系统管理员权限");
        var session = repo.session(Long.parseLong(actor.sessionId()), false);
        if (session == null || session.revoked() || session.userId() != current.id() || !session.expiresAt().isAfter(java.time.Instant.now())) {
            throw new BusinessException(401, "SESSION_REVOKED", "登录状态已失效");
        }
    }

    /**
     * 以操作者、操作和请求键隔离幂等记录；相同内容重试回放原响应，不重复执行写入。
     * 记录行、管理员守卫及用户行都在同一事务中锁定，保证权限与最后管理员约束不被并发绕过。
     */
    private Mutation idempotent(AuthActor actor, String operation, String key, Object payload, Supplier<Mutation> work) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{8,64}"))
            throw new BusinessException(400, "INVALID_IDEMPOTENCY_KEY", "需要 8–64 位幂等键");
        String hash = TokenSecrets.hash(json.writeValueAsString(payload));
        try {
            return transactions.execute(tx -> {
                repo.sql().update("INSERT INTO idempotency_record(actor_id,operation,request_key,payload_hash,status,expires_at) VALUES (?,?,?,?,'IN_PROGRESS',UTC_TIMESTAMP(3)+INTERVAL 1 DAY) ON DUPLICATE KEY UPDATE id=id",
                        Long.parseLong(actor.userId()), operation, key, hash);
                var row = repo.sql().queryForMap("SELECT *,expires_at<=UTC_TIMESTAMP(3) AS expired FROM idempotency_record WHERE actor_id=? AND operation=? AND request_key=? FOR UPDATE", Long.parseLong(actor.userId()), operation, key);
                // 通过守卫行串行化账号管理，即使两个管理员互相修改，也保持一致的加锁顺序。
                repo.sql().queryForObject("SELECT id FROM identity_admin_guard WHERE id=1 FOR UPDATE", Integer.class);
                long target = operation.contains(":") ? Long.parseLong(operation.substring(operation.indexOf(':') + 1)) : Long.parseLong(actor.userId());
                repo.sql().queryForList("SELECT id FROM `user` WHERE id IN (?,?) ORDER BY id FOR UPDATE", Long.class, Long.parseLong(actor.userId()), target);
                requireAdmin(actor);
                // 先重新确认管理员权限，再在同一行锁内重置过期记录，避免无限期回放旧响应。
                if ("SUCCEEDED".equals(row.get("status")) && ((Number) row.get("expired")).intValue() == 1) {
                    repo.sql().update("UPDATE idempotency_record SET payload_hash=?,status='IN_PROGRESS',http_status=NULL,response_body=NULL,expires_at=UTC_TIMESTAMP(3)+INTERVAL 1 DAY WHERE id=?", hash, row.get("id"));
                    row.put("payload_hash", hash);
                    row.put("status", "IN_PROGRESS");
                }
                if (!hash.equals(row.get("payload_hash")))
                    throw new BusinessException(409, "IDEMPOTENCY_KEY_REUSED", "同一幂等键对应的请求内容不同");
                if ("SUCCEEDED".equals(row.get("status")))
                    return new Mutation(((Number) row.get("http_status")).intValue(), json.readValue(row.get("response_body").toString(), Object.class), true);
                Mutation result = work.get();
                repo.sql().update("UPDATE idempotency_record SET status='SUCCEEDED',http_status=?,response_body=? WHERE id=?", result.status(), json.writeValueAsString(result.data()), row.get("id"));
                return result;
            });
        } catch (DuplicateKeyException failure) {
            throw new BusinessException(409, "USERNAME_EXISTS", "用户名已存在");
        }
    }

    public Mutation create(AuthActor actor, String key, AuthCommands.CreateUser command) {
        if (command.initialPassword().getBytes(StandardCharsets.UTF_8).length > 72)
            throw new BusinessException(400, "VALIDATION_ERROR", "密码最多 72 个 UTF-8 字节");
        // BCrypt 计算放在管理员锁外，缩短持锁时间；初始密码既不持久化也不返回。
        String encoded = passwords.encode(command.initialPassword());
        var normalized = new AuthCommands.CreateUser(command.username(), tokens.passwordFingerprint(command.initialPassword()), command.displayName(), new TreeSet<>(command.roleCodes()));
        return idempotent(actor, "CREATE_USER", key, normalized, () -> {
            long id = repo.createUser(command.username(), encoded, command.displayName(), command.roleCodes());
            repo.audit(Long.parseLong(actor.userId()), "CREATE_USER", id, null, "ACTIVE");
            return new Mutation(201, view(repo.user(id, false)), false);
        });
    }

    /**
     * 预览只检查输入和已有账号，不保存人员名单或密码，也不预占账号名。
     * 学号、工号共用登录名唯一空间；发现碰撞时应核对源数据，不自动改名或覆盖账号。
     */
    public Map<String, Object> previewPersonnel(AuthActor actor, AuthCommands.PersonnelImport command) {
        requireAdmin(actor);
        var prepared = PersonnelAccounts.prepare(command);
        var errors = new ArrayList<>(prepared.errors());
        var items = new ArrayList<Map<String, Object>>();
        if (errors.isEmpty()) {
            for (int index = 0; index < prepared.users().size(); index++) {
                var user = prepared.users().get(index);
                int row = index + 1;
                if (repo.username(user.username(), false) != null)
                    errors.add(new PersonnelAccounts.Error(row, "number", "USERNAME_EXISTS", "账号已存在，不覆盖原账号"));
                items.add(Map.of("row", row, "username", user.username(), "displayName", user.displayName(), "roles", user.roleCodes()));
            }
        }
        return Map.of("total", command.rows().size(), "canImport", errors.isEmpty(), "items", items, "errors", errors);
    }

    /**
     * 批量创建学生和教师：重新校验输入，在管理员守卫锁内复核唯一性并整批写入。
     * 预览不是授权或唯一性承诺；确认时任一冲突会回滚整批，不能部分创建或重置旧密码。
     * 幂等载荷使用密码 HMAC 指纹，记录及响应不含明文密码或 BCrypt 摘要。
     */
    public Mutation importPersonnel(AuthActor actor, String key, AuthCommands.PersonnelImport command) {
        requireAdmin(actor);
        var prepared = PersonnelAccounts.prepare(command);
        if (!prepared.errors().isEmpty())
            throw new BusinessException(422, "IMPORT_HAS_ERRORS", "人员资料存在错误，请先预览并修正");
        var normalized = prepared.users().stream().map(user -> new AuthCommands.CreateUser(user.username(),
                tokens.passwordFingerprint(user.initialPassword()), user.displayName(), user.roleCodes())).toList();
        // 密码计算放在事务外，限制单批规模，避免长时间占用全局账号管理锁。
        var encoded = prepared.users().stream().map(user -> passwords.encode(user.initialPassword())).toList();
        return idempotent(actor, "IMPORT_PERSONNEL", key, normalized, () -> {
            for (var user : prepared.users()) {
                if (repo.username(user.username(), false) != null)
                    throw new BusinessException(409, "USERNAME_EXISTS", "本批包含已存在账号，请重新预览；未创建任何账号");
            }
            var items = new ArrayList<Map<String, Object>>();
            for (int index = 0; index < prepared.users().size(); index++) {
                var user = prepared.users().get(index);
                long id = repo.createUser(user.username(), encoded.get(index), user.displayName(), user.roleCodes());
                repo.audit(Long.parseLong(actor.userId()), "IMPORT_PERSONNEL", id, null, "ACTIVE");
                items.add(view(repo.user(id, false)));
            }
            return new Mutation(201, Map.of("created", items.size(), "items", items), false);
        });
    }

    private IdentityRepository.User target(long id, long version) {
        var user = repo.user(id, false);
        if (user == null) throw new BusinessException(404, "NOT_FOUND", "账号不存在");
        if (user.version() != version)
            throw new BusinessException(409, "VERSION_CONFLICT", "账号已被修改，请刷新后重试");
        return user;
    }

    /**
     * 只有从有效系统管理员降权或停用时才检查剩余人数；调用方已经持有管理员守卫锁。
     */
    private void keepAdministrator(IdentityRepository.User target, boolean willBeAdmin) {
        if ("ACTIVE".equals(target.status()) && repo.roles(target.id()).contains("SYSTEM_ADMIN") && !willBeAdmin) {
            long count = repo.sql().queryForObject("SELECT COUNT(*) FROM `user` u JOIN user_role ur ON ur.user_id=u.id JOIN `role` r ON r.id=ur.role_id WHERE u.status='ACTIVE' AND r.code='SYSTEM_ADMIN'", Long.class);
            if (count <= 1) throw new BusinessException(409, "LAST_SYSTEM_ADMIN", "至少保留一个有效系统管理员");
        }
    }

    /**
     * 撤销目标用户所有未撤销会话；提交后补写缓存，数据库记录立即成为权威状态。
     */
    private void invalidate(long id) {
        var ids = repo.sql().queryForList("SELECT id FROM user_session WHERE user_id=? AND revoked_at IS NULL", Long.class, id);
        repo.sql().update("UPDATE user_session SET revoked_at=UTC_TIMESTAMP(3),version=version+1 WHERE user_id=? AND revoked_at IS NULL", id);
        ids.forEach(cache::afterCommit);
    }

    public Mutation status(AuthActor actor, String key, long id, AuthCommands.Status command) {
        return idempotent(actor, "USER_STATUS:" + id, key, command, () -> {
            var user = target(id, command.expectedVersion());
            keepAdministrator(user, "ACTIVE".equals(command.status()) && repo.roles(id).contains("SYSTEM_ADMIN"));
            repo.sql().update("UPDATE `user` SET status=?,auth_version=auth_version+1 WHERE id=?", command.status(), id);
            invalidate(id);
            repo.audit(Long.parseLong(actor.userId()), "USER_STATUS", id, user.status(), command.status());
            return new Mutation(200, view(repo.user(id, false)), false);
        });
    }

    public Mutation roles(AuthActor actor, String key, long id, AuthCommands.Roles command) {
        // 将无序角色集合排序后再计算幂等指纹，避免仅顺序不同被误判为不同请求。
        var normalized = new AuthCommands.Roles(new TreeSet<>(command.roleCodes()), command.expectedVersion());
        return idempotent(actor, "USER_ROLES:" + id, key, normalized, () -> {
            var user = target(id, command.expectedVersion());
            keepAdministrator(user, "ACTIVE".equals(user.status()) && command.roleCodes().contains("SYSTEM_ADMIN"));
            repo.setRoles(id, command.roleCodes());
            repo.sql().update("UPDATE `user` SET auth_version=auth_version+1 WHERE id=?", id);
            invalidate(id);
            repo.audit(Long.parseLong(actor.userId()), "USER_ROLES", id, null, null);
            return new Mutation(200, view(repo.user(id, false)), false);
        });
    }

    public Map<String, Object> list(AuthActor actor, int page, int size, String username, String status) {
        requireAdmin(actor);
        if (page < 1 || size < 1 || size > 100 || page > 100000 || username.length() > 64 || (!status.isEmpty() && !Set.of("ACTIVE", "DISABLED").contains(status))) {
            throw new BusinessException(400, "VALIDATION_ERROR", "分页或筛选参数无效");
        }
        String where = " WHERE (?='' OR username=?) AND (?='' OR status=?)";
        long total = repo.sql().queryForObject("SELECT COUNT(*) FROM `user`" + where, Long.class, username, username, status, status);
        var ids = repo.sql().queryForList("SELECT id FROM `user`" + where + " ORDER BY id LIMIT ? OFFSET ?", Long.class, username, username, status, status, size, (page - 1) * size);
        return Map.of("items", ids.stream().map(id -> view(repo.user(id, false))).toList(), "page", page, "size", size, "total", total);
    }
}
