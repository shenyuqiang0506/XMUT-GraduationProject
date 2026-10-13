package org.shen.graduationproject.application;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

import lombok.RequiredArgsConstructor;
import org.shen.graduationproject.domain.AvailabilityCommands.*;
import org.shen.graduationproject.domain.TimeWindow;
import org.shen.graduationproject.domain.EquipmentIndex;
import org.shen.graduationproject.infrastructure.*;
import org.shen.platform.contracts.AuthActor;
import org.shen.platform.support.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/**
 * 在一致性快照中查询资源日历和场地、设备可行组合。
 * 开放规则、有效来源覆盖与已知占用共同决定状态；缺少占用记录不代表可用。
 * 查询同时计算外部占用、有效暂占和批准占用；查询本身不创建暂占。
 */
@Service
@Profile("business")
@RequiredArgsConstructor
public class AvailabilityQueries {
    private final JdbcTemplate sql;
    private final CatalogAccess access;
    private final BookingPolicies policies;

    /**
     * 按资源 ID 分组的一次查询数据，包含开放规则、来源覆盖和外部占用，供多个候选组合复用。
     */
    private record Snapshot(Map<Long, List<Map<String, Object>>> rules,
                            Map<Long, List<Map<String, Object>>> coverage,
                            Map<Long, List<Map<String, Object>>> occupancy) {
    }

    private static long id(Map<String, Object> row) {
        return ((Number) row.get("id")).longValue();
    }

    private static String placeholders(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }

    private static Map<Long, List<Map<String, Object>>> group(List<Map<String, Object>> rows) {
        var groups = new HashMap<Long, List<Map<String, Object>>>();
        for (var row : rows)
            groups.computeIfAbsent(((Number) row.get("resource_id")).longValue(), k -> new ArrayList<>()).add(row);
        return groups;
    }

    /**
     * 批量读取所有候选在目标窗口相关的数据，SQL 使用半开区间重叠条件，端点相接不算占用。
     */
    private Snapshot load(List<Map<String, Object>> resources, TimeWindow window) {
        return load(resources, window, null);
    }

    private Snapshot load(List<Map<String, Object>> resources, TimeWindow window, Long excludeApplication) {
        return load(resources,window,excludeApplication,null);
    }

    private Snapshot load(List<Map<String, Object>> resources, TimeWindow window, Long excludeApplication,Long excludeWaitlist) {
        if (resources.isEmpty()) return new Snapshot(Map.of(), Map.of(), Map.of());
        Object[] ids = resources.stream().map(AvailabilityQueries::id).toArray();
        String in = placeholders(ids.length);
        var args = new ArrayList<>(Arrays.asList(ids));
        args.add(Timestamp.from(window.end()));
        args.add(Timestamp.from(window.start()));
        var occupancy = new ArrayList<>(sql.queryForList("SELECT resource_id,start_at,end_at,data_mode,'EXTERNAL' type FROM external_occupancy WHERE resource_id IN (" + in + ") AND active=1 AND start_at<? AND end_at>?", args.toArray()));
        var platformArgs = new ArrayList<>(args);
        if (excludeApplication != null) platformArgs.add(excludeApplication);
        if (excludeWaitlist != null) platformArgs.add(excludeWaitlist);
        // 过期 HOLD 即使尚未被定时任务更新为 RELEASED，也不能继续阻挡可用组合。
        occupancy.addAll(sql.queryForList("SELECT o.resource_id,o.start_at,o.end_at,COALESCE(a.data_mode,wg.data_mode) data_mode,CASE WHEN a.status='CONFLICT_REVIEW' THEN 'CONFLICT_REVIEW' WHEN w.status='OFFERED' THEN 'WAITLIST_HOLD' ELSE o.status END type FROM resource_occupancy o LEFT JOIN activity_application a ON a.id=o.application_id LEFT JOIN waitlist_entry w ON w.id=o.waitlist_entry_id LEFT JOIN waitlist_group wg ON wg.id=w.group_id WHERE o.resource_id IN (" + in + ") AND o.start_at<? AND o.end_at>? AND (o.status='CONFIRMED' OR (o.status='HOLD' AND o.expires_at>UTC_TIMESTAMP(3)))"
                + (excludeApplication == null ? "" : " AND (o.application_id IS NULL OR o.application_id<>?)")
                + (excludeWaitlist == null ? "" : " AND (o.waitlist_entry_id IS NULL OR o.waitlist_entry_id<>?)"), platformArgs.toArray()));
        // 批量读取规则、覆盖和两类占用，避免逐资源 N+1 查询。
        return new Snapshot(group(sql.queryForList("SELECT * FROM resource_opening_rule WHERE resource_id IN (" + in + ") AND enabled=1", ids)),
                group(sql.queryForList("SELECT * FROM availability_coverage WHERE resource_id IN (" + in + ") AND active=1 AND from_at<? AND to_at>?", args.toArray())),
                group(occupancy));
    }

    private static LocalDate date(Object value) {
        return value instanceof Date d ? d.toLocalDate() : (LocalDate) value;
    }

    private static LocalTime time(Object value) {
        return value instanceof Time t ? t.toLocalTime() : (LocalTime) value;
    }

    /**
     * 把星期规则与有效日期映射到查询起始日的上海时间区间；搜索已限定同一自然日。
     */
    private List<TimeWindow> openings(List<Map<String, Object>> rows, TimeWindow window) {
        LocalDate day = window.start().atZone(TimeWindow.ZONE).toLocalDate();
        return rows.stream().filter(r -> ((Number) r.get("weekday")).intValue() == day.getDayOfWeek().getValue()
                        && !day.isBefore(date(r.get("valid_from"))) && !day.isAfter(date(r.get("valid_to"))))
                .map(r -> new TimeWindow(day.atTime(time(r.get("start_local"))).atZone(TimeWindow.ZONE).toInstant(),
                        day.atTime(time(r.get("end_local"))).atZone(TimeWindow.ZONE).toInstant())).toList();
    }

    /**
     * 只用有效的 COMPLETE 区间并集证明整个目标窗口。
     * 存在部分证据或完整证据的缺口则返回 PARTIAL，完全缺失或证据失效返回 UNKNOWN。
     */
    private String coverage(Map<String, Object> resource, TimeWindow window, BookingPolicies.Active policy, Instant now, Snapshot data) {
        if (policy == null) return "UNKNOWN";
        var complete = new ArrayList<TimeWindow>();
        boolean partial = false;
        Instant cutoff = now.minusSeconds(policy.settings().coverageFreshnessMinutes() * 60L);
        for (var row : data.coverage().getOrDefault(id(resource), List.of())) {
            // 只有目录所声明来源、相同数据模式的未过期证据才可证明空闲。
            Instant checked = UtcTimes.instant(row.get("checked_at"));
            if (!Objects.equals(row.get("source_system"), resource.get("source_system"))
                    || !Objects.equals(row.get("data_mode"), resource.get("data_mode"))
                    || checked.isBefore(cutoff) || checked.isAfter(now)) continue;
            if ("COMPLETE".equals(row.get("completeness")))
                complete.add(new TimeWindow(UtcTimes.instant(row.get("from_at")), UtcTimes.instant(row.get("to_at"))));
            else partial = true;
        }
        if (TimeWindow.covered(window, complete)) return "COMPLETE";
        return partial || !complete.isEmpty() ? "PARTIAL" : "UNKNOWN";
    }

    /**
     * 判定顺序：资源与审批组状态、开放时间、已知占用、完整覆盖。
     * 只有全部条件满足才返回 AVAILABLE，缺少证明不能被当作空闲。
     */
    private String status(Map<String, Object> resource, TimeWindow window, BookingPolicies.Active policy, Instant now, Snapshot data) {
        if (!"OPEN".equals(resource.get("platform_status")) || !"ACTIVE".equals(resource.get("group_status"))
                || ("REAL".equals(resource.get("data_mode")) && !"YES".equals(resource.get("source_borrowable")))
                || ("EQUIPMENT".equals(resource.get("resource_type")) && !"GOOD".equals(resource.get("condition_status"))))
            return "CLOSED";
        if (!TimeWindow.covered(window, openings(data.rules().getOrDefault(id(resource), List.of()), window)))
            return "CLOSED";
        for (var row : data.occupancy().getOrDefault(id(resource), List.of()))
            if (Objects.equals(row.get("data_mode"), resource.get("data_mode"))) return "BUSY";
        return "COMPLETE".equals(coverage(resource, window, policy, now, data)) ? "AVAILABLE" : "UNKNOWN";
    }

    /**
     * 将数据库时间字段转换为接口约定的日期与本地时刻字符串，供详情和规则编辑页面使用。
     */
    public List<Map<String, Object>> openingRules(long resource) {
        var result = new ArrayList<Map<String, Object>>();
        for (var row : sql.queryForList("SELECT weekday,start_local,end_local,valid_from,valid_to,enabled FROM resource_opening_rule WHERE resource_id=? ORDER BY weekday,start_local", resource)) {
            result.add(Map.of("weekday", row.get("weekday"), "startLocal", time(row.get("start_local")).toString(),
                    "endLocal", time(row.get("end_local")).toString(), "validFrom", date(row.get("valid_from")).toString(),
                    "validTo", date(row.get("valid_to")).toString(), "enabled", ((Number) row.get("enabled")).intValue() == 1));
        }
        return result;
    }

    /**
     * 日历读取采用可重复读事务，防止占用、覆盖与策略分别读到不同批次的提交状态。
     * 允许最多 31 天窗口，展示时把记录裁剪到请求范围，并隐藏其他人的活动内容。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> calendar(AuthActor actor, long resource, OffsetDateTime from, OffsetDateTime to) {
        var window = TimeWindow.of(from, to);
        window.limitDays(31);
        var rows = sql.queryForList("SELECT r.*,ag.status group_status,e.condition_status FROM resource r LEFT JOIN approval_group ag ON ag.id=r.approval_group_id LEFT JOIN equipment_detail e ON e.resource_id=r.id WHERE r.id=? AND (" + access.visibleSql(actor) + ")", resource);
        if (rows.isEmpty()) throw new BusinessException(404, "NOT_FOUND", "资源不存在或不可见");
        var row = rows.getFirst();
        String mode = row.get("data_mode").toString();
        policies.mode(mode);
        var policy = policies.active(mode);
        Instant now = policies.now();
        var data = load(rows, window);
        var intervals = new ArrayList<Map<String, Object>>();
        for (var busy : data.occupancy().getOrDefault(resource, List.of())) {
            if (!mode.equals(busy.get("data_mode"))) continue;
            Instant start = UtcTimes.instant(busy.get("start_at")), end = UtcTimes.instant(busy.get("end_at"));
            intervals.add(Map.of("startAt", TimeWindow.display(start.isBefore(window.start()) ? window.start() : start),
                    "endAt", TimeWindow.display(end.isAfter(window.end()) ? window.end() : end), "type", busy.get("type")));
        }
        var evidence = new ArrayList<Map<String, Object>>();
        for (var proof : data.coverage().getOrDefault(resource, List.of())) {
            if (!mode.equals(proof.get("data_mode")) || !row.get("source_system").equals(proof.get("source_system"))) continue;
            Instant checked = UtcTimes.instant(proof.get("checked_at"));
            String completeness = policy == null || checked.isAfter(now)
                    || checked.isBefore(now.minusSeconds(policy.settings().coverageFreshnessMinutes() * 60L))
                    ? "UNKNOWN" : proof.get("completeness").toString();
            Instant start = UtcTimes.instant(proof.get("from_at")), end = UtcTimes.instant(proof.get("to_at"));
            evidence.add(Map.of("from", TimeWindow.display(start.isBefore(window.start()) ? window.start() : start),
                    "to", TimeWindow.display(end.isAfter(window.end()) ? window.end() : end),
                    "coverageStatus", completeness, "checkedAt", TimeWindow.display(checked)));
        }
        // 日历只返回区间和粗粒度类别，不返回来源编号、他人姓名或活动备注。
        return Map.of("resourceId", Long.toString(resource), "dataMode", mode, "from", TimeWindow.display(window.start()),
                "to", TimeWindow.display(window.end()), "intervals", intervals, "coverageStatus", coverage(row, window, policy, now, data),
                "openingRules", openingRules(resource), "checkedAt", TimeWindow.display(now), "coverageIntervals", evidence);
    }

    /**
     * 写申请、批准和来源变更复用同一可用性判据。调用方必须先锁协调行及完整资源集合。
     * excludeApplication 只排除正在审批/复核的本申请，不能排除其他用户的占用。
     */
    public List<Map<String, Object>> inspect(List<Long> ids, TimeWindow window, BookingPolicies.Active policy, Long excludeApplication) {
        return inspect(ids,window,policy,excludeApplication,null);
    }

    /** 候补确认只排除自己的 offer；其他 OFFERED、申请 HOLD 和已批准占用仍参与冲突检查。 */
    public List<Map<String,Object>> inspect(List<Long> ids,TimeWindow window,BookingPolicies.Active policy,Long excludeApplication,Long excludeWaitlist) {
        var rows = sql.queryForList("SELECT r.*,ag.status group_status,e.condition_status,e.equipment_type FROM resource r LEFT JOIN approval_group ag ON ag.id=r.approval_group_id LEFT JOIN equipment_detail e ON e.resource_id=r.id WHERE r.id IN (" + placeholders(ids.size()) + ") ORDER BY r.id", ids.toArray());
        if (rows.size() != ids.size()) throw new BusinessException(404, "NOT_FOUND", "资源不存在或不可见");
        var data = load(rows, window, excludeApplication,excludeWaitlist);
        Instant now = policies.now();
        for (var row : rows) {
            row.put("checked_availability", status(row, window, policy, now, data));
            row.put("checked_coverage",coverage(row,window,policy,now,data));
        }
        return rows;
    }

    /**
     * 查找一处场地与同审批组、同校区设备的可行组合。
     * 先校验规则及人数、设施条件，再批量检查时间；每处场地只返回一套确定的设备选择，而非枚举所有排列。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> search(AuthActor actor, Search input) {
        policies.mode(input.dataMode());
        TimeWindow window = TimeWindow.of(input.startAt(), input.endAt());
        window.singleDay();
        window.limitDays(1);
        var requirements = input.equipmentRequirements() == null ? List.<EquipmentRequirement>of() : input.equipmentRequirements();
        if (requirements.stream().map(EquipmentRequirement::type).distinct().count() != requirements.size()
                || requirements.stream().mapToInt(EquipmentRequirement::count).sum() > 40)
            throw new BusinessException(400, "VALIDATION_ERROR", "设备类型不能重复，单个组合最多 40 件设备");
        Instant now = policies.now();
        var policy = policies.active(input.dataMode());
        if (policy == null) return Map.of("items", List.of(), "page", input.page(), "size", input.size(), "total", 0,
                "checkedAt", TimeWindow.display(now), "unavailableReason", "POLICY_NOT_CONFIGURED");
        policies.eligible(window, policy, now);
        var args = new ArrayList<Object>(List.of(input.dataMode(), input.participantCount()));
        StringBuilder where = new StringBuilder(" WHERE (" + access.visibleSql(actor) + ") AND r.platform_status='OPEN' AND ag.status='ACTIVE' AND r.resource_type='VENUE' AND r.data_mode=? AND r.capacity>=?");
        if (input.campus() != null && !input.campus().isBlank()) {
            where.append(" AND r.campus=?");
            args.add(input.campus());
        }
        if (input.preferredBuilding() != null && !input.preferredBuilding().isBlank()) {
            where.append(" AND r.building=?");
            args.add(input.preferredBuilding());
        }
        for (String feature : input.requiredFeatures() == null ? Set.<String>of() : new TreeSet<>(input.requiredFeatures())) {
            where.append(" AND EXISTS(SELECT 1 FROM resource_feature f WHERE f.resource_id=r.id AND f.feature_code=? AND LOWER(TRIM(f.feature_value)) NOT IN ('no','false','0','unknown','否','无','未知','待核实','不详') AND LENGTH(TRIM(f.feature_value))>0)");
            args.add(feature);
        }
        // 多取一条用于判断候选超限。超过计算上限时明确要求缩小条件，不截断候选后虚报完整结果。
        var venues = sql.queryForList("SELECT r.*,ag.status group_status FROM resource r JOIN approval_group ag ON ag.id=r.approval_group_id" + where + " ORDER BY r.id LIMIT 2001", args.toArray());
        if (venues.size() > 2000) throw new BusinessException(422, "QUERY_TOO_BROAD", "请缩小场地筛选范围");
        var equipment = new ArrayList<Map<String, Object>>();
        if (!requirements.isEmpty() && !venues.isEmpty()) {
            var groups = venues.stream().map(r -> ((Number) r.get("approval_group_id")).longValue()).distinct().toList();
            var equipmentArgs = new ArrayList<Object>(List.of(input.dataMode()));
            equipmentArgs.addAll(groups);
            equipmentArgs.addAll(requirements.stream().map(EquipmentRequirement::type).toList());
            equipment.addAll(sql.queryForList("SELECT r.*,ag.status group_status,e.condition_status,e.equipment_type FROM resource r JOIN approval_group ag ON ag.id=r.approval_group_id JOIN equipment_detail e ON e.resource_id=r.id WHERE (" + access.visibleSql(actor) + ") AND r.platform_status='OPEN' AND ag.status='ACTIVE' AND r.data_mode=? AND e.condition_status='GOOD' AND r.approval_group_id IN (" + placeholders(groups.size()) + ") AND e.equipment_type IN (" + placeholders(requirements.size()) + ") ORDER BY r.id LIMIT 5001", equipmentArgs.toArray()));
            if (equipment.size() > 5000) throw new BusinessException(422, "QUERY_TOO_BROAD", "请缩小设备筛选范围");
        }
        var all = new ArrayList<>(venues);
        all.addAll(equipment);
        Snapshot data = load(all, window);
        // 设备先独立通过可用性检查，再按组、校区、类型匹配场地；SQL 已按 ID 排序，选择结果可重复。
        var usable = equipment.stream().filter(r -> "AVAILABLE".equals(status(r, window, policy, now, data))).toList();
        var index = new EquipmentIndex(usable);
        var plans = matchPlans(venues, requirements, index, window, policy, now, data, input.dataMode());
        // 分页作用于已通过全部校验的场地组合，total 是可行场地数，而非初始候选数。
        int offset = Math.min(plans.size(), (input.page() - 1) * input.size());
        // 结果是当前快照，不创建暂占；后续提交联合预约时必须再次锁资源并校验。
        return Map.of("items", plans.subList(offset, Math.min(plans.size(), offset + input.size())), "page", input.page(),
                "size", input.size(), "total", plans.size(), "checkedAt", TimeWindow.display(now));
    }

    /** 组合匹配与 SQL 查询分开，复用设备索引，避免每个场地、每类需求反复扫描全体设备。 */
    private List<Map<String, Object>> matchPlans(List<Map<String, Object>> venues, List<EquipmentRequirement> requirements,
            EquipmentIndex index, TimeWindow window, BookingPolicies.Active policy, Instant now, Snapshot data, String mode) {
        var plans = new ArrayList<Map<String, Object>>();
        for (var venue : venues) {
            if (!"AVAILABLE".equals(status(venue, window, policy, now, data))) continue;
            var selected = new ArrayList<String>();
            boolean enough = true;
            for (var need : requirements) {
                var candidates = index.select(venue, need.type(), need.count());
                if (candidates.size() != need.count()) {
                    enough = false;
                    break;
                }
                selected.addAll(candidates);
            }
            if (!enough) continue;
            selected.sort(Comparator.comparingLong(Long::parseLong));
            var members = new ArrayList<>(selected);
            members.add(Long.toString(id(venue)));
            members.sort(Comparator.comparingLong(Long::parseLong));
            plans.add(Map.of("venueId", Long.toString(id(venue)), "venueName", venue.get("name"), "equipmentIds", selected,
                    "resourceIds", members, "approvalGroupId", venue.get("approval_group_id").toString(), "availability", "AVAILABLE",
                    "coverageStatus", "COMPLETE", "dataMode", mode, "checkedAt", TimeWindow.display(now), "policyVersion", policy.version()));
        }
        return plans;
    }
}
