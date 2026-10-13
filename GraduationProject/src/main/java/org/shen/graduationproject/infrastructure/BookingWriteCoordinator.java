package org.shen.graduationproject.infrastructure;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.shen.platform.contracts.AuthActor;
import org.shen.platform.support.BusinessException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 首版联合预约写入协调器：幂等行之后、资源和申请之前锁固定协调行。
 * 来源批次可能只修改场地，却需要释放该申请的全部配套设备；协调行稳定其关联集合。
 * 不缓存、不代替资源行锁，所有关联资源仍按主键升序逐个加锁。
 */
@Component
@Profile("business")
@RequiredArgsConstructor
public class BookingWriteCoordinator {
    private final JdbcTemplate sql;
    private final ApplicationEventPublisher events;

    public record ResourcesChanged(AuthActor actor, List<Long> resourceIds, Map<String,Integer> counts) {}

    public void lock() {
        sql.queryForObject("SELECT id FROM booking_write_guard WHERE id=1 FOR UPDATE", Integer.class);
    }

    /** 调用方已取得协调行；把关联申请的其他资源也纳入同一升序锁集合。 */
    public void resources(Collection<Long> changed) {
        var ids = new TreeSet<>(changed);
        for (long resource : changed)
            ids.addAll(sql.queryForList("SELECT ar2.resource_id FROM application_resource ar JOIN activity_application a ON a.id=ar.application_id JOIN application_resource ar2 ON ar2.application_id=a.id WHERE ar.resource_id=? AND a.status IN ('PENDING_APPROVAL','APPROVED','CONFLICT_REVIEW')", Long.class, resource));
        for (long id : ids) {
            var found = sql.queryForList("SELECT id FROM resource WHERE id=? FOR UPDATE", Long.class, id);
            if (found.isEmpty()) throw new BusinessException(404, "NOT_FOUND", "资源不存在或不可见");
        }
    }

    /** 同步事件在原写事务内执行，任一申请处理失败都会回滚整个来源/规则变更。 */
    public Map<String,Integer> changed(AuthActor actor, Collection<Long> ids) {
        var counts=new HashMap<String,Integer>(); counts.put("rejected",0); counts.put("conflictReview",0);
        events.publishEvent(new ResourcesChanged(actor, List.copyOf(ids),counts));
        return Map.copyOf(counts);
    }
}
