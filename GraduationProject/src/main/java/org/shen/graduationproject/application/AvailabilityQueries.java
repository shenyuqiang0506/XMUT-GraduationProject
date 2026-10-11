package org.shen.graduationproject.application;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

import lombok.RequiredArgsConstructor;
import org.shen.graduationproject.domain.AvailabilityCommands.*;
import org.shen.graduationproject.domain.TimeWindow;
import org.shen.graduationproject.infrastructure.*;
import org.shen.platform.contracts.AuthActor;
import org.shen.platform.support.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Profile("business & !m1")
@RequiredArgsConstructor
public class AvailabilityQueries {
    private final JdbcTemplate sql;
    private final CatalogAccess access;
    private final BookingPolicies policies;

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

    private Snapshot load(List<Map<String, Object>> resources, TimeWindow window) {
        if (resources.isEmpty()) return new Snapshot(Map.of(), Map.of(), Map.of());
        Object[] ids = resources.stream().map(AvailabilityQueries::id).toArray();
        String in = placeholders(ids.length);
        var args = new ArrayList<>(Arrays.asList(ids));
        args.add(Timestamp.from(window.end()));
        args.add(Timestamp.from(window.start()));
        // 批量读取三个维度，避免每个候选资源单独查询带来的 N+1 开销。
        return new Snapshot(group(sql.queryForList("SELECT * FROM resource_opening_rule WHERE resource_id IN (" + in + ") AND enabled=1", ids)),
                group(sql.queryForList("SELECT * FROM availability_coverage WHERE resource_id IN (" + in + ") AND active=1 AND from_at<? AND to_at>?", args.toArray())),
                group(sql.queryForList("SELECT resource_id,start_at,end_at,data_mode FROM external_occupancy WHERE resource_id IN (" + in + ") AND active=1 AND start_at<? AND end_at>?", args.toArray())));
    }

    private static LocalDate date(Object value) {
        return value instanceof Date d ? d.toLocalDate() : (LocalDate) value;
    }

    private static LocalTime time(Object value) {
        return value instanceof Time t ? t.toLocalTime() : (LocalTime) value;
    }

    private List<TimeWindow> openings(List<Map<String, Object>> rows, TimeWindow window) {
        LocalDate day = window.start().atZone(TimeWindow.ZONE).toLocalDate();
        return rows.stream().filter(r -> ((Number) r.get("weekday")).intValue() == day.getDayOfWeek().getValue()
                        && !day.isBefore(date(r.get("valid_from"))) && !day.isAfter(date(r.get("valid_to"))))
                .map(r -> new TimeWindow(day.atTime(time(r.get("start_local"))).atZone(TimeWindow.ZONE).toInstant(),
                        day.atTime(time(r.get("end_local"))).atZone(TimeWindow.ZONE).toInstant())).toList();
    }

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

    public List<Map<String, Object>> openingRules(long resource) {
        var result = new ArrayList<Map<String, Object>>();
        for (var row : sql.queryForList("SELECT weekday,start_local,end_local,valid_from,valid_to,enabled FROM resource_opening_rule WHERE resource_id=? ORDER BY weekday,start_local", resource)) {
            result.add(Map.of("weekday", row.get("weekday"), "startLocal", time(row.get("start_local")).toString(),
                    "endLocal", time(row.get("end_local")).toString(), "validFrom", date(row.get("valid_from")).toString(),
                    "validTo", date(row.get("valid_to")).toString(), "enabled", ((Number) row.get("enabled")).intValue() == 1));
        }
        return result;
    }

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
                    "endAt", TimeWindow.display(end.isAfter(window.end()) ? window.end() : end), "type", "EXTERNAL"));
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
        var usable = equipment.stream().filter(r -> "AVAILABLE".equals(status(r, window, policy, now, data))).toList();
        var plans = new ArrayList<Map<String, Object>>();
        for (var venue : venues) {
            if (!"AVAILABLE".equals(status(venue, window, policy, now, data))) continue;
            var selected = new ArrayList<String>();
            boolean enough = true;
            for (var need : requirements) {
                var candidates = usable.stream().filter(r -> Objects.equals(r.get("approval_group_id"), venue.get("approval_group_id"))
                                && Objects.equals(r.get("campus"), venue.get("campus")) && need.type().equals(r.get("equipment_type")))
                        .limit(need.count()).toList();
                if (candidates.size() != need.count()) {
                    enough = false;
                    break;
                }
                candidates.forEach(r -> selected.add(Long.toString(id(r))));
            }
            if (!enough) continue;
            selected.sort(Comparator.comparingLong(Long::parseLong));
            var members = new ArrayList<>(selected);
            members.add(Long.toString(id(venue)));
            members.sort(Comparator.comparingLong(Long::parseLong));
            plans.add(Map.of("venueId", Long.toString(id(venue)), "venueName", venue.get("name"), "equipmentIds", selected,
                    "resourceIds", members, "approvalGroupId", venue.get("approval_group_id").toString(), "availability", "AVAILABLE",
                    "coverageStatus", "COMPLETE", "dataMode", input.dataMode(), "checkedAt", TimeWindow.display(now), "policyVersion", policy.version()));
        }
        int offset = Math.min(plans.size(), (input.page() - 1) * input.size());
        // 结果是当前快照，不创建暂占；后续提交联合预约时必须再次锁资源并校验。
        return Map.of("items", plans.subList(offset, Math.min(plans.size(), offset + input.size())), "page", input.page(),
                "size", input.size(), "total", plans.size(), "checkedAt", TimeWindow.display(now));
    }
}
