package org.shen.graduationproject.application;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

import lombok.RequiredArgsConstructor;
import org.shen.graduationproject.domain.*;
import org.shen.graduationproject.domain.ApplicationCommands.*;
import org.shen.graduationproject.infrastructure.*;
import org.shen.platform.contracts.AuthActor;
import org.shen.platform.support.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

/**
 * 联合申请用例：身份来自已验真的 AuthActor，资源成员创建后不可修改。
 * 写入经过共同幂等事务及协调锁，按用户配额、升序资源、申请行的顺序执行。
 * 提交、批准、拒绝、取消和人工冲突复核均在同一事务内更新全部资源。
 */
@Service
@Profile("business")
@RequiredArgsConstructor
public class Applications {
    private final JdbcTemplate sql;
    private final CatalogIdempotency idempotency;
    private final BookingWriteCoordinator coordinator;
    private final BookingPolicies policies;
    private final ApplicationLifecycle lifecycle;
    private final CatalogAccess access;
    private final AuthorizedApplicants applicants;
    private final ObjectMapper json;
    private final ApplicantQuota quota;
    private final WaitlistPriority priority;
    private final DataModes dataModes;

    private long number(Object value) {
        return ((Number) value).longValue();
    }

    /**
     * 编号、时区和集合顺序规范化，避免相同组合仅因数组顺序变化而产生不同幂等指纹。
     */
    private Create normalize(Create input) {
        return ApplicationRequests.normalize(input);
    }

    public CatalogIdempotency.Result create(AuthActor actor, String key, Create request) {
        ApplicationRules.applicant(actor);
        var input = normalize(request);
        return idempotency.execute(actor, "APPLICATION_CREATE", key, input, () -> {
            long uid = CatalogAccess.id(actor.userId()), venue = CatalogAccess.id(input.venueId());
            // 稳定用户守卫使两个不同组合的并发提交也不能超过同一用户配额。
            quota.lock(uid);
            var ids = new TreeSet<Long>();
            ids.add(venue);
            input.equipmentIds().forEach(id -> ids.add(CatalogAccess.id(id)));
            var members = List.copyOf(ids);
            coordinator.resources(members);
            var place = sql.queryForMap("SELECT * FROM resource WHERE id=?", venue);
            String mode = place.get("data_mode").toString();
            policies.mode(mode);
            var policy = policies.active(mode);
            if (policy == null)
                throw new BusinessException(422, "POLICY_NOT_CONFIGURED", "请先由管理员配置已确认的预约策略");
            var window = TimeWindow.of(input.startAt(), input.endAt());
            Instant now = policies.now();
            policies.eligible(window, policy, now);
            lifecycle.expireForResources(members, actor);
            quota.requireSpace(uid, policy.settings().maxActiveApplicationsPerUser(), null);
            priority.requireNoQueue(members, window, mode);
            Long group = place.get("approval_group_id") == null ? null : number(place.get("approval_group_id"));
            var rows = lifecycle.validate(members, venue, input.participantCount(), input.requiredFeatures(), mode, group, window, policy, null);
            Instant hold = ApplicationRules.holdUntil(policies.now(), window.start(), policy.settings().pendingApprovalTtlMinutes(), policy.settings().bookingLeadMinutes());
            long id = persist(actor, input, rows, mode, group, policy, hold, null);
            return new CatalogIdempotency.Result(201, detail(actor, id), false);
        });
    }

    /**
     * 调用方已完成全组锁定、资格/额度和来源校验；确认 offer 时在此事务原子转交占用。
     */
    public long persist(AuthActor actor, Create input, List<Map<String, Object>> rows, String mode, long group,
                        BookingPolicies.Active policy, Instant hold, Long offerId) {
        long uid = CatalogAccess.id(actor.userId());
        var window = TimeWindow.of(input.startAt(), input.endAt());
        var applicant = Map.of("userId", actor.userId(), "username", actor.username(), "displayName", actor.displayName());
        sql.update("INSERT INTO activity_application(applicant_id,activity_name,description,participant_count,start_at,end_at,status,approval_group_id,hold_expires_at,policy_version,policy_snapshot,applicant_snapshot,required_features,data_mode) VALUES (?,?,?,?,?,?,'PENDING_APPROVAL',?,?,?,?,?,?,?)",
                uid, input.activityName(), input.description(), input.participantCount(), Timestamp.from(window.start()), Timestamp.from(window.end()),
                group, Timestamp.from(hold), policy.version(), json.writeValueAsString(policy.settings()), json.writeValueAsString(applicant),
                json.writeValueAsString(input.requiredFeatures()), mode);
        long id = sql.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        for (var resource : rows) {
            var snapshot = new LinkedHashMap<String, Object>();
            snapshot.put("id", resource.get("id").toString());
            snapshot.put("name", resource.get("name"));
            snapshot.put("resourceType", resource.get("resource_type"));
            snapshot.put("campus", resource.get("campus"));
            snapshot.put("equipmentType", resource.get("equipment_type"));
            snapshot.put("sourceId", resource.get("source_id"));
            sql.update("INSERT INTO application_resource(application_id,resource_id,resource_snapshot) VALUES (?,?,?)", id, resource.get("id"), json.writeValueAsString(snapshot));
            if (offerId == null)
                sql.update("INSERT INTO resource_occupancy(resource_id,application_id,start_at,end_at,status,expires_at) VALUES (?,?,?,?,'HOLD',?)",
                        resource.get("id"), id, Timestamp.from(window.start()), Timestamp.from(window.end()), Timestamp.from(hold));
        }
        if (offerId != null) {
            int transferred = sql.update("UPDATE resource_occupancy SET application_id=?,waitlist_entry_id=NULL,waitlist_group_id=NULL,expires_at=? WHERE waitlist_entry_id=? AND status='HOLD'", id, Timestamp.from(hold), offerId);
            if (transferred != rows.size()) throw new IllegalStateException("候补占用成员转交数量不一致");
        }
        var row = lifecycle.row(id, false);
        lifecycle.event(row, "APPLICATION_SUBMITTED", actor, null);
        return id;
    }

    private List<String> actions(AuthActor actor, Map<String, Object> row) {
        var actions = new ArrayList<String>();
        String status = row.get("status").toString();
        Instant now = policies.now(), start = UtcTimes.instant(row.get("start_at"));
        boolean expired = "PENDING_APPROVAL".equals(status) && !UtcTimes.instant(row.get("hold_expires_at")).isAfter(now);
        if (expired || Set.of("COMPLETED", "REJECTED", "EXPIRED", "CANCELLED").contains(status)) return actions;
        if ((lifecycle.owns(actor, row) || lifecycle.manages(actor, row))
                && ApplicationRules.canCancel(now, start, lifecycle.snapshotPolicy(row).cancelLeadMinutes()))
            actions.add("CANCEL");
        if (lifecycle.manages(actor, row)) {
            if ("PENDING_APPROVAL".equals(status)) {
                actions.add("APPROVE");
                actions.add("REJECT");
            }
            if ("CONFLICT_REVIEW".equals(status)) {
                actions.add("RESOLVE_CANCEL");
                if (now.isBefore(start)) actions.add("REVALIDATE");
            }
        }
        return actions;
    }

    private Map<String, Object> summary(AuthActor actor, Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        result.put("applicationId", row.get("id").toString());
        result.put("activityName", row.get("activity_name"));
        result.put("status", row.get("status"));
        result.put("version", row.get("version"));
        result.put("participantCount", row.get("participant_count"));
        result.put("dataMode", row.get("data_mode"));
        result.put("startAt", TimeWindow.display(UtcTimes.instant(row.get("start_at"))));
        result.put("endAt", TimeWindow.display(UtcTimes.instant(row.get("end_at"))));
        result.put("holdExpiresAt", row.get("hold_expires_at") == null ? null : TimeWindow.display(UtcTimes.instant(row.get("hold_expires_at"))));
        result.put("policyVersion", row.get("policy_version"));
        result.put("approvalGroupId", row.get("approval_group_id").toString());
        result.put("lastReason", row.get("last_reason"));
        result.put("updatedAt", TimeWindow.display(UtcTimes.instant(row.get("updated_at"))));
        result.put("allowedActions", actions(actor, row));
        return result;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> detail(AuthActor actor, long id) {
        var row = lifecycle.row(id, false);
        lifecycle.visible(actor, row);
        var result = summary(actor, row);
        result.put("description", row.get("description"));
        result.put("applicant", json.readValue(row.get("applicant_snapshot").toString(), Map.class));
        result.put("resourceIds", lifecycle.members(id).stream().map(Object::toString).toList());
        result.put("resources", sql.queryForList("SELECT resource_snapshot FROM application_resource WHERE application_id=? ORDER BY resource_id", String.class, id)
                .stream().map(value -> json.readValue(value, Map.class)).toList());
        result.put("approvalRecords", sql.queryForList("SELECT operator_id,decision,reason,from_version,created_at FROM approval_record WHERE application_id=? ORDER BY id", id)
                .stream().map(record -> {
                    var item = new LinkedHashMap<String, Object>();
                    item.put("operatorId", record.get("operator_id") == null ? null : record.get("operator_id").toString());
                    item.put("decision", record.get("decision"));
                    item.put("reason", record.get("reason"));
                    item.put("fromVersion", record.get("from_version"));
                    item.put("createdAt", TimeWindow.display(UtcTimes.instant(record.get("created_at"))));
                    return item;
                }).toList());
        return result;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> list(AuthActor actor, boolean administrative, String status, int page, int size) {
        if (page < 1 || page > 100000 || size < 1 || size > 100 || (!status.isBlank() && !ApplicationRules.STATUSES.contains(status)))
            throw new BusinessException(400, "VALIDATION_ERROR", "状态或分页参数无效");
        var args = new ArrayList<Object>();
        String where;
        if (administrative) {
            access.administrator(actor);
            where = access.system(actor) ? "1=1" : "EXISTS(SELECT 1 FROM approval_group_member gm JOIN approval_group ag ON ag.id=gm.group_id WHERE gm.group_id=a.approval_group_id AND gm.user_id=? AND ag.status='ACTIVE')";
            if (!access.system(actor)) args.add(CatalogAccess.id(actor.userId()));
        } else {
            where = "a.applicant_id=?";
            args.add(CatalogAccess.id(actor.userId()));
        }
        if (!status.isBlank()) {
            where += " AND a.status=?";
            args.add(status);
        }
        if (!dataModes.demoAllowed()) where += " AND a.data_mode='REAL'";
        long total = sql.queryForObject("SELECT COUNT(*) FROM activity_application a WHERE " + where, Long.class, args.toArray());
        args.add(size);
        args.add((page - 1) * size);
        var rows = sql.queryForList("SELECT a.* FROM activity_application a WHERE " + where + " ORDER BY a.id DESC LIMIT ? OFFSET ?", args.toArray());
        return Map.of("items", rows.stream().map(r -> summary(actor, r)).toList(), "page", page, "size", size, "total", total);
    }

    public CatalogIdempotency.Result act(AuthActor actor, String key, long id, Action input, String decision) {
        var initial = lifecycle.row(id, false);
        lifecycle.visible(actor, initial);
        if (!"CANCEL".equals(decision)) {
            if (!lifecycle.manages(actor, initial))
                throw new BusinessException(404, "NOT_FOUND", "申请不存在或不在审批范围");
            if ("REJECT".equals(decision) && (input.reason() == null || input.reason().isBlank()))
                throw new BusinessException(400, "VALIDATION_ERROR", "驳回理由不能为空");
            // 已完成动作的同键重试仍可返回原结果；新动作会在锁内检查状态和版本。
            if ("APPROVE".equals(decision) && "PENDING_APPROVAL".equals(initial.get("status")))
                applicants.requireEligible(number(initial.get("applicant_id")));
        }
        return idempotency.execute(actor, "APPLICATION_" + decision + ":" + id, key, input, () -> perform(actor, id, input, decision, false));
    }

    public CatalogIdempotency.Result resolve(AuthActor actor, String key, long id, Resolve input) {
        var initial = lifecycle.row(id, false);
        lifecycle.visible(actor, initial);
        if (!lifecycle.manages(actor, initial))
            throw new BusinessException(404, "NOT_FOUND", "申请不存在或不在审批范围");
        if ("REVALIDATE".equals(input.action()) && "CONFLICT_REVIEW".equals(initial.get("status")))
            applicants.requireEligible(number(initial.get("applicant_id")));
        return idempotency.execute(actor, "APPLICATION_RESOLVE:" + id, key, input,
                () -> perform(actor, id, new Action(input.expectedVersion(), input.reason()), input.action(), true));
    }

    /**
     * 到期动作使用错误结果而非抛异常，使 EXPIRED 状态与释放先提交，再向客户端返回 409。
     */
    private CatalogIdempotency.Result perform(AuthActor actor, long id, Action input, String decision, boolean conflictResolution) {
        coordinator.resources(lifecycle.members(id));
        var row = lifecycle.row(id, true);
        lifecycle.visible(actor, row);
        if (!"CANCEL".equals(decision) || conflictResolution) {
            if (!lifecycle.manages(actor, row))
                throw new BusinessException(404, "NOT_FOUND", "申请不存在或不在审批范围");
        }
        if (((Number) row.get("version")).intValue() != input.expectedVersion())
            throw new BusinessException(409, "VERSION_CONFLICT", "申请版本已变化，请刷新");
        if (lifecycle.expireIfDue(row, actor)) {
            boolean holdExpired = "PENDING_APPROVAL".equals(row.get("status"));
            return new CatalogIdempotency.Result(409, Map.of("code", holdExpired ? "APPROVAL_HOLD_EXPIRED" : "INVALID_STATE",
                    "message", holdExpired ? "申请已到期，请重新查询并申请" : "活动已结束，当前申请已完成", "application", detail(actor, id)), false);
        }
        String status = row.get("status").toString();
        if (conflictResolution) {
            if (!"CONFLICT_REVIEW".equals(status))
                throw new BusinessException(409, "INVALID_STATE", "仅冲突待复核申请可执行该操作");
        } else if ("CANCEL".equals(decision)) {
            if (!Set.of("PENDING_APPROVAL", "APPROVED", "CONFLICT_REVIEW").contains(status))
                throw new BusinessException(409, "INVALID_STATE", "当前状态不能取消");
        } else if (!"PENDING_APPROVAL".equals(status))
            throw new BusinessException(409, "INVALID_STATE", "当前状态不能审批");
        if ("CANCEL".equals(decision) && !conflictResolution
                && !ApplicationRules.canCancel(policies.now(), UtcTimes.instant(row.get("start_at")), lifecycle.snapshotPolicy(row).cancelLeadMinutes()))
            throw new BusinessException(422, "CANCELLATION_DEADLINE_PASSED", "已超过取消截止时间，请联系管理员");
        if ("APPROVE".equals(decision) || "REVALIDATE".equals(decision)) {
            if (!policies.now().isBefore(UtcTimes.instant(row.get("start_at"))))
                throw new BusinessException(422, "ACTIVITY_ALREADY_STARTED", "活动开始后不能批准或恢复批准");
            lifecycle.validateExisting(row);
        }
        String target = switch (decision) {
            case "APPROVE", "REVALIDATE" -> "APPROVED";
            case "REJECT" -> "REJECTED";
            case "CANCEL" -> "CANCELLED";
            default -> throw new IllegalArgumentException("未知申请动作");
        };
        lifecycle.transition(row, target, input.reason(), actor);
        var result = detail(actor, id);
        if (Set.of("REJECTED", "CANCELLED").contains(target))
            result.put("releasedResourceIds", lifecycle.members(id).stream().map(Object::toString).toList());
        return new CatalogIdempotency.Result(200, result, false);
    }
}
