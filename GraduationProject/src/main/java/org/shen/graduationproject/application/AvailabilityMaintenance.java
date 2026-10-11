package org.shen.graduationproject.application;

import java.sql.*;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.shen.graduationproject.domain.AvailabilityCommands.*;
import org.shen.graduationproject.domain.TimeWindow;
import org.shen.graduationproject.infrastructure.*;
import org.shen.platform.contracts.AuthActor;
import org.shen.platform.support.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@Profile("business & !m1")
@RequiredArgsConstructor
public class AvailabilityMaintenance {
    private final JdbcTemplate sql;
    private final CatalogAccess access;
    private final CatalogIdempotency idempotency;
    private final ResourceCatalog catalog;
    private final AvailabilityQueries queries;
    private final BookingPolicies policies;

    public CatalogIdempotency.Result opening(AuthActor actor, String key, long resource, OpeningRules input) {
        access.administrator(actor);
        access.manage(actor, resource);
        for (var rule : input.rules()) {
            if (!rule.startLocal().isBefore(rule.endLocal()) || rule.validTo().isBefore(rule.validFrom())
                    || rule.startLocal().getNano() != 0 || rule.endLocal().getNano() != 0)
                throw new BusinessException(400, "TIME_RANGE_INVALID", "开放规则需要当天有效时段，日期不能倒置，时刻精度为秒");
        }
        return idempotency.execute(actor, "OPENING_RULES:" + resource, key, input, () -> {
            var current = catalog.raw(resource, true);
            access.manage(actor, resource);
            policies.mode(current.get("data_mode").toString());
            if (((Number) current.get("version")).intValue() != input.expectedVersion())
                throw new BusinessException(409, "VERSION_CONFLICT", "资源规则已变化，请刷新");
            sql.update("DELETE FROM resource_opening_rule WHERE resource_id=?", resource);
            for (var rule : input.rules())
                sql.update("INSERT INTO resource_opening_rule(resource_id,weekday,start_local,end_local,valid_from,valid_to,enabled) VALUES (?,?,?,?,?,?,?)",
                        resource, rule.weekday(), Time.valueOf(rule.startLocal()), Time.valueOf(rule.endLocal()),
                        Date.valueOf(rule.validFrom()), Date.valueOf(rule.validTo()), rule.enabled() ? 1 : 0);
            sql.update("UPDATE resource SET version=version+1 WHERE id=?", resource);
            catalog.audit(actor, "OPENING_RULES", "RESOURCE", resource, null, null);
            return new CatalogIdempotency.Result(200, Map.of("resourceId", Long.toString(resource), "version", input.expectedVersion() + 1,
                    "rules", queries.openingRules(resource)), false);
        });
    }

    private Map<String, Object> view(Map<String, Object> row) {
        return Map.of("id", row.get("id").toString(), "resourceId", row.get("resource_id").toString(), "sourceSystem", row.get("source_system"),
                "sourceKey", row.get("source_key"), "startAt", TimeWindow.display(UtcTimes.instant(row.get("start_at"))),
                "endAt", TimeWindow.display(UtcTimes.instant(row.get("end_at"))), "reasonCode", row.get("reason_code"),
                "active", ((Number) row.get("active")).intValue() == 1, "version", row.get("version"), "dataMode", row.get("data_mode"));
    }

    public CatalogIdempotency.Result create(AuthActor actor, String key, long resource, Occupancy input) {
        access.administrator(actor);
        access.manage(actor, resource);
        if (input.sourceKey().startsWith("__fragment_"))
            throw new BusinessException(400, "VALIDATION_ERROR", "来源编号不能使用内部保留前缀");
        var window = TimeWindow.of(input.startAt(), input.endAt());
        window.limitDays(31);
        try {
            return idempotency.execute(actor, "EXTERNAL_CREATE:" + resource, key, input, () -> {
                var row = catalog.raw(resource, true);
                access.manage(actor, resource);
                policies.mode(row.get("data_mode").toString());
                // 手工占用有审计而没有导入批次；它不能自行提供完整覆盖证明。
                sql.update("INSERT INTO external_occupancy(resource_id,source_key,start_at,end_at,reason_code,source_system,observed_at,data_mode) VALUES (?,?,?,?,?,?,UTC_TIMESTAMP(3),?)",
                        resource, input.sourceKey(), Timestamp.from(window.start()), Timestamp.from(window.end()), input.reasonCode(), input.sourceSystem(), row.get("data_mode"));
                long id = sql.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                catalog.audit(actor, "EXTERNAL_CREATE", "RESOURCE", resource, null, "ACTIVE");
                return new CatalogIdempotency.Result(201, view(sql.queryForMap("SELECT * FROM external_occupancy WHERE id=?", id)), false);
            });
        } catch (DuplicateKeyException conflict) {
            throw new BusinessException(409, "SOURCE_KEY_EXISTS", "来源占用编号已存在，请使用更新接口");
        }
    }

    public Map<String, Object> list(AuthActor actor, long resource, int page, int size) {
        access.administrator(actor);
        access.manage(actor, resource);
        if (page < 1 || page > 100000 || size < 1 || size > 100)
            throw new BusinessException(400, "VALIDATION_ERROR", "分页参数无效");
        policies.mode(catalog.raw(resource, false).get("data_mode").toString());
        long total = sql.queryForObject("SELECT COUNT(*) FROM external_occupancy WHERE resource_id=?", Long.class, resource);
        var rows = sql.queryForList("SELECT * FROM external_occupancy WHERE resource_id=? ORDER BY id LIMIT ? OFFSET ?", resource, size, (page - 1) * size);
        return Map.of("items", rows.stream().map(this::view).toList(), "page", page, "size", size, "total", total);
    }

    public CatalogIdempotency.Result patch(AuthActor actor, String key, long id, OccupancyUpdate input) {
        access.administrator(actor);
        var owners = sql.queryForList("SELECT resource_id FROM external_occupancy WHERE id=?", Long.class, id);
        if (owners.isEmpty()) throw new BusinessException(404, "NOT_FOUND", "占用不存在或不可见");
        long resource = owners.getFirst();
        access.manage(actor, resource);
        var window = TimeWindow.of(input.startAt(), input.endAt());
        window.limitDays(31);
        return idempotency.execute(actor, "EXTERNAL_UPDATE:" + id, key, input, () -> {
            // 固定顺序：幂等记录 → 资源 → 占用。后续预约写入也遵守资源锁协议。
            var owner = catalog.raw(resource, true);
            access.manage(actor, resource);
            policies.mode(owner.get("data_mode").toString());
            var row = sql.queryForMap("SELECT * FROM external_occupancy WHERE id=? FOR UPDATE", id);
            if (((Number) row.get("version")).intValue() != input.expectedVersion())
                throw new BusinessException(409, "VERSION_CONFLICT", "占用已变化，请刷新");
            sql.update("UPDATE external_occupancy SET start_at=?,end_at=?,reason_code=?,active=?,observed_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=?",
                    Timestamp.from(window.start()), Timestamp.from(window.end()), input.reasonCode(), input.active() ? 1 : 0, id);
            catalog.audit(actor, "EXTERNAL_UPDATE", "RESOURCE", resource, null, input.active() ? "ACTIVE" : "INACTIVE");
            return new CatalogIdempotency.Result(200, view(sql.queryForMap("SELECT * FROM external_occupancy WHERE id=?", id)), false);
        });
    }
}
