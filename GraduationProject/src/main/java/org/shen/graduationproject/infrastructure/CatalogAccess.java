package org.shen.graduationproject.infrastructure;

import java.util.List;

import org.shen.platform.contracts.AuthActor;
import org.shen.platform.support.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("business & !m1")
public class CatalogAccess {
    private final JdbcTemplate sql;

    public CatalogAccess(JdbcTemplate sql) {
        this.sql = sql;
    }

    public static long id(String value) {
        try {
            long id = Long.parseLong(value);
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (RuntimeException invalid) {
            throw new BusinessException(400, "VALIDATION_ERROR", "编号需要有效的正整数字符串");
        }
    }

    public boolean system(AuthActor actor) {
        return actor.roles().contains("SYSTEM_ADMIN");
    }

    public void administrator(AuthActor actor) {
        if (!system(actor) && !actor.roles().contains("RESOURCE_ADMIN"))
            throw new BusinessException(403, "FORBIDDEN", "需要资源管理权限");
    }

    public void systemAdministrator(AuthActor actor) {
        if (!system(actor)) throw new BusinessException(403, "FORBIDDEN", "需要系统管理员权限");
    }

    public boolean groupMember(AuthActor actor, long group) {
        return sql.queryForObject("SELECT COUNT(*) FROM approval_group_member m JOIN approval_group g ON g.id=m.group_id WHERE m.user_id=? AND m.group_id=? AND g.status='ACTIVE'", Long.class, Long.parseLong(actor.userId()), group) > 0;
    }

    public boolean manages(AuthActor actor, long resource) {
        if (system(actor)) return true;
        if (!actor.roles().contains("RESOURCE_ADMIN")) return false;
        return sql.queryForObject("SELECT COUNT(*) FROM resource r WHERE r.id=? AND (EXISTS(SELECT 1 FROM resource_manager m WHERE m.resource_id=r.id AND m.user_id=?) OR EXISTS(SELECT 1 FROM approval_group_member gm JOIN approval_group g ON g.id=gm.group_id WHERE gm.group_id=r.approval_group_id AND gm.user_id=? AND g.status='ACTIVE'))", Long.class, resource, Long.parseLong(actor.userId()), Long.parseLong(actor.userId())) > 0;
    }

    public void manage(AuthActor actor, long resource) {
        if (!manages(actor, resource)) throw new BusinessException(404, "NOT_FOUND", "资源不存在或不在管理范围内");
    }

    public void createInGroup(AuthActor actor, String group) {
        administrator(actor);
        if (!system(actor) && (group == null || !groupMember(actor, id(group))))
            throw new BusinessException(403, "FORBIDDEN", "新资源必须属于已授权审批组");
    }

    public String visibleSql(AuthActor actor) {
        if (system(actor)) return "1=1";
        if (actor.roles().contains("RESOURCE_ADMIN"))
            return "(EXISTS(SELECT 1 FROM resource_manager rm WHERE rm.resource_id=r.id AND rm.user_id=" + Long.parseLong(actor.userId()) + ") OR EXISTS(SELECT 1 FROM approval_group_member gm JOIN approval_group ag ON ag.id=gm.group_id WHERE gm.group_id=r.approval_group_id AND gm.user_id=" + Long.parseLong(actor.userId()) + " AND ag.status='ACTIVE'))";
        return "r.platform_status='OPEN' AND EXISTS(SELECT 1 FROM approval_group ag WHERE ag.id=r.approval_group_id AND ag.status='ACTIVE')";
    }
}
