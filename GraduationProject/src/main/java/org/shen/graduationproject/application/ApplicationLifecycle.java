package org.shen.graduationproject.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.shen.graduationproject.domain.*;
import org.shen.graduationproject.domain.AvailabilityCommands.PolicySettings;
import org.shen.graduationproject.infrastructure.*;
import org.shen.platform.contracts.*;
import org.shen.platform.support.BusinessException;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;

/**
 * 申请与整组占用的状态机、到期补偿及来源冲突处理。
 * 每次状态变化同时写状态版本、全部占用、审批历史、审计和 Outbox，禁止半组释放。
 * 所有写入口先锁 booking_write_guard，再按升序锁资源，最后锁申请行。
 */
@Service
@Profile("business")
@RequiredArgsConstructor
@Slf4j
public class ApplicationLifecycle {
    private final JdbcTemplate sql;
    private final BookingWriteCoordinator coordinator;
    private final BookingPolicies policies;
    private final AvailabilityQueries availability;
    private final CatalogAccess access;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    @Value("${JWT_AUDIENCE}") private String deliveryScope;

    public List<Long> members(long id) {
        return sql.queryForList("SELECT resource_id FROM application_resource WHERE application_id=? ORDER BY resource_id", Long.class, id);
    }

    public Map<String, Object> row(long id, boolean lock) {
        var rows = sql.queryForList("SELECT * FROM activity_application WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
        if (rows.isEmpty()) throw new BusinessException(404, "NOT_FOUND", "申请不存在或不可见");
        return rows.getFirst();
    }

    public boolean manages(AuthActor actor, Map<String, Object> row) {
        return access.system(actor) || (actor.roles().contains("RESOURCE_ADMIN")
                && access.groupMember(actor, ((Number) row.get("approval_group_id")).longValue()));
    }

    public boolean owns(AuthActor actor, Map<String, Object> row) { return actor.userId().equals(row.get("applicant_id").toString()); }

    public void visible(AuthActor actor, Map<String, Object> row) {
        if (!owns(actor, row) && !manages(actor, row)) throw new BusinessException(404, "NOT_FOUND", "申请不存在或不可见");
        policies.mode(row.get("data_mode").toString());
    }

    public PolicySettings snapshotPolicy(Map<String, Object> row) { return json.readValue(row.get("policy_snapshot").toString(), PolicySettings.class); }

    public TimeWindow window(Map<String, Object> row) { return new TimeWindow(UtcTimes.instant(row.get("start_at")), UtcTimes.instant(row.get("end_at"))); }

    /** 写入侧传入完整锁定集合，批准/复核只排除本申请，其他占用仍参与冲突判断。 */
    public List<Map<String, Object>> validate(List<Long> ids, long venue, int participants, Set<String> features,
            String mode, Long group, TimeWindow window, BookingPolicies.Active policy, Long excludeApplication) {
        return validate(ids,venue,participants,features,mode,group,window,policy,excludeApplication,null,false);
    }

    /** 候补可登记真实冲突，但不能绕过容量、开放、完整来源覆盖；确认时仅排除自己的 offer。 */
    public List<Map<String,Object>> validate(List<Long> ids,long venue,int participants,Set<String> features,String mode,
            Long group,TimeWindow window,BookingPolicies.Active policy,Long excludeApplication,Long excludeWaitlist,boolean allowBusy) {
        var rows = availability.inspect(ids, window, policy, excludeApplication,excludeWaitlist);
        var venues = rows.stream().filter(r -> "VENUE".equals(r.get("resource_type"))).toList();
        if (venues.size() != 1 || !Long.toString(venue).equals(venues.getFirst().get("id").toString()))
            throw new BusinessException(422, "INVALID_RESOURCE_COMBINATION", "申请需要一处场地，其余成员必须为单件设备");
        var place = venues.getFirst();
        if (place.get("capacity") == null || ((Number) place.get("capacity")).intValue() < participants)
            throw new BusinessException(422, "CAPACITY_NOT_SATISFIED", "场地容量未知或不足以容纳活动人数");
        if (group == null || place.get("approval_group_id") == null)
            throw new BusinessException(422, "APPROVAL_GROUP_MISMATCH", "场地尚未配置有效审批组");
        for (var r : rows) {
            if (!mode.equals(r.get("data_mode")) || !group.equals(r.get("approval_group_id"))
                    || !Objects.equals(place.get("campus"), r.get("campus")))
                throw new BusinessException(422, "APPROVAL_GROUP_MISMATCH", "全部资源必须为同模式、同审批组和同校区");
            String status = r.get("checked_availability").toString();
            if ("BUSY".equals(status) && !allowBusy) throw new BusinessException(409, "RESOURCE_CONFLICT", "所选资源已有外部或平台占用，请重新查询");
            if ("CLOSED".equals(status)) throw new BusinessException(422, "RESOURCE_CLOSED", "资源未开放、规则不满足或来源不允许借用");
            if (!("AVAILABLE".equals(status)||("BUSY".equals(status)&&allowBusy&&"COMPLETE".equals(r.get("checked_coverage")))))
                throw new BusinessException(422, "COVERAGE_UNKNOWN", "来源覆盖缺失、部分或过期，无法确认可用");
        }
        for (String feature : features) {
            long count = sql.queryForObject("SELECT COUNT(*) FROM resource_feature WHERE resource_id=? AND feature_code=? AND LOWER(TRIM(feature_value)) NOT IN ('no','false','0','unknown','否','无','未知','待核实','不详') AND LENGTH(TRIM(feature_value))>0", Long.class, venue, feature);
            if (count == 0) throw new BusinessException(422, "FEATURE_NOT_SATISFIED", "场地不满足已声明的设施要求");
        }
        return rows;
    }

    public void validateExisting(Map<String, Object> row) {
        String mode = row.get("data_mode").toString();
        var policy = policies.active(mode);
        if (policy == null) throw new BusinessException(422, "POLICY_NOT_CONFIGURED", "当前没有已确认的预约策略");
        long id = ((Number) row.get("id")).longValue();
        var ids = members(id);
        long venue = sql.queryForObject("SELECT ar.resource_id FROM application_resource ar JOIN resource r ON r.id=ar.resource_id WHERE ar.application_id=? AND r.resource_type='VENUE'", Long.class, id);
        Set<String> features = new TreeSet<>(json.readValue(row.get("required_features").toString(), List.class));
        validate(ids, venue, ((Number) row.get("participant_count")).intValue(), features, mode,
                ((Number) row.get("approval_group_id")).longValue(), window(row), policy, id);
        if (java.time.Duration.between(window(row).start(), window(row).end()).compareTo(java.time.Duration.ofMinutes(policy.settings().maxDurationMinutes())) > 0)
            throw new BusinessException(422, "POLICY_CHANGED", "活动时长不再满足当前策略，请重新申请");
    }

    /** 新申请和每次合法状态变化各产生唯一版本事件，M4 可据此可靠投递通知。 */
    public void event(Map<String, Object> row, String type, AuthActor actor, String from) {
        long id = ((Number) row.get("id")).longValue();
        String trace = TraceIds.acceptOrCreate(MDC.get(TraceIds.MDC_KEY));
        sql.update("INSERT INTO audit_log(actor_id,action,object_type,object_id,from_status,to_status,reason,trace_id) VALUES (?,?,'APPLICATION',?,?,?,?,?)",
                actor == null ? null : Long.parseLong(actor.userId()), type, id, from, row.get("status"), row.get("last_reason"), trace);
        var recipients = new TreeSet<String>();
        recipients.add(row.get("applicant_id").toString());
        sql.queryForList("SELECT user_id FROM approval_group_member WHERE group_id=?", Long.class, row.get("approval_group_id"))
                .forEach(user -> recipients.add(user.toString()));
        String eventId = UUID.randomUUID().toString();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("eventId", eventId); payload.put("eventType", type); payload.put("aggregateType", "APPLICATION");
        payload.put("aggregateId", Long.toString(id)); payload.put("aggregateVersion", row.get("version"));
        payload.put("occurredAt", TimeWindow.display(policies.now())); payload.put("recipientIds", recipients);
        payload.put("payload", Map.of("status", row.get("status"), "reason", Objects.toString(row.get("last_reason"), "")));
        payload.put("traceId", trace);
        payload.put("deliveryScope",deliveryScope);
        sql.update("INSERT INTO outbox_event(event_id,event_type,aggregate_type,aggregate_id,aggregate_version,payload,delivery_scope) VALUES (?,?,'APPLICATION',?,?,?,?)",
                eventId, type, id, row.get("version"), json.writeValueAsString(payload),deliveryScope);
    }

    /** 必须已锁全部成员和申请行；数量不符视为数据不变量被破坏，整个事务回滚。 */
    public Map<String, Object> transition(Map<String, Object> row, String status, String reason, AuthActor actor) {
        long id = ((Number) row.get("id")).longValue();
        int version = ((Number) row.get("version")).intValue();
        String from = row.get("status").toString();
        int changed = sql.update("UPDATE activity_application SET status=?,hold_expires_at=NULL,last_reason=?,version=version+1 WHERE id=? AND version=?", status, reason, id, version);
        if (changed != 1) throw new BusinessException(409, "VERSION_CONFLICT", "申请状态已变化，请刷新");
        int occupancy;
        if ("APPROVED".equals(status))
            occupancy = sql.update("UPDATE resource_occupancy SET status='CONFIRMED',expires_at=NULL WHERE application_id=? AND status IN ('HOLD','CONFIRMED')", id);
        else if ("CONFLICT_REVIEW".equals(status))
            occupancy = sql.queryForObject("SELECT COUNT(*) FROM resource_occupancy WHERE application_id=? AND status='CONFIRMED'", Integer.class, id);
        else occupancy = sql.update("UPDATE resource_occupancy SET status='RELEASED',expires_at=NULL,released_at=UTC_TIMESTAMP(3) WHERE application_id=? AND status<>'RELEASED'", id);
        if (occupancy != members(id).size()) throw new IllegalStateException("申请占用成员数量不一致");
        sql.update("INSERT INTO approval_record(application_id,operator_id,decision,reason,from_version) VALUES (?,?,?,?,?)",
                id, actor == null ? null : Long.parseLong(actor.userId()), status, reason, version);
        var current = row(id, false);
        event(current, "APPLICATION_" + ("CONFLICT_REVIEW".equals(status) ? "SOURCE_CONFLICT" : status), actor, from);
        if(Set.of("REJECTED","CANCELLED","EXPIRED","COMPLETED").contains(status))coordinator.released(members(id));
        return current;
    }

    /** 到期检查以数据库时间为准；CONFLICT_REVIEW 必须人工处置，不自动完成。 */
    public boolean expireIfDue(Map<String, Object> row, AuthActor actor) {
        Instant now = policies.now();
        if ("PENDING_APPROVAL".equals(row.get("status")) && !UtcTimes.instant(row.get("hold_expires_at")).isAfter(now)) {
            transition(row, "EXPIRED", "APPROVAL_HOLD_EXPIRED", actor); return true;
        }
        if ("APPROVED".equals(row.get("status")) && !UtcTimes.instant(row.get("end_at")).isAfter(now)) {
            transition(row, "COMPLETED", "ACTIVITY_ENDED", actor); return true;
        }
        return false;
    }

    /** 提交请求顺便处理关联资源上已到期的申请，不依赖定时器准点运行。 */
    public void expireForResources(List<Long> resources, AuthActor actor) {
        var ids = new TreeSet<Long>();
        for (long resource : resources)
            ids.addAll(sql.queryForList("SELECT a.id FROM application_resource ar JOIN activity_application a ON a.id=ar.application_id WHERE ar.resource_id=? AND a.status IN ('PENDING_APPROVAL','APPROVED')", Long.class, resource));
        for (long id : ids) expireIfDue(row(id, true), actor);
    }

    @Scheduled(initialDelayString="${BOOKING_LIFECYCLE_INITIAL_DELAY_MS:3000}", fixedDelayString="${BOOKING_LIFECYCLE_DELAY_MS:5000}")
    public void tick() {
        // 先只读取候选 ID，逐笔按协议加锁；进程重启或多实例重复扫描不重复生成事件。
        try {
            var ids = sql.queryForList("SELECT id FROM activity_application WHERE (status='PENDING_APPROVAL' AND hold_expires_at<=UTC_TIMESTAMP(3)) OR (status='APPROVED' AND end_at<=UTC_TIMESTAMP(3)) ORDER BY id LIMIT 100", Long.class);
            for (long id : ids) {
                try {
                    transactions.executeWithoutResult(tx -> {
                        coordinator.lock(); coordinator.resources(members(id));
                        expireIfDue(row(id, true), null);
                    });
                } catch (RuntimeException failure) { log.error("申请到期处理失败，applicationId={}", id, failure); }
            }
        } catch (RuntimeException failure) { log.error("申请到期扫描失败", failure); }
    }

    /** 新来源、覆盖或规则不能偷偷使已批准活动继续显示正常；恢复必须显式复核。 */
    @EventListener
    public void changed(BookingWriteCoordinator.ResourcesChanged event) {
        var ids = new TreeSet<Long>();
        for (long resource : event.resourceIds())
            ids.addAll(sql.queryForList("SELECT a.id FROM application_resource ar JOIN activity_application a ON a.id=ar.application_id WHERE ar.resource_id=? AND a.status IN ('PENDING_APPROVAL','APPROVED')", Long.class, resource));
        for (long id : ids) {
            var row = row(id, true);
            if (expireIfDue(row, event.actor())) continue;
            try { validateExisting(row); }
            catch (BusinessException conflict) {
                String status = row.get("status").toString();
                if ("PENDING_APPROVAL".equals(status)) {
                    transition(row, "REJECTED", "SOURCE_CONFLICT:" + conflict.code(), event.actor());
                    event.counts().merge("rejected",1,Integer::sum);
                } else if ("APPROVED".equals(status)) {
                    transition(row, "CONFLICT_REVIEW", "SOURCE_CONFLICT:" + conflict.code(), event.actor());
                    event.counts().merge("conflictReview",1,Integer::sum);
                }
            }
        }
    }
}
