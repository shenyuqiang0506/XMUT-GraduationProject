package org.shen.graduationproject.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.shen.graduationproject.domain.AvailabilityCommands.*;
import org.shen.graduationproject.domain.TimeWindow;
import org.shen.graduationproject.infrastructure.*;
import org.shen.platform.contracts.AuthActor;
import org.shen.platform.support.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@Profile("business")
@RequiredArgsConstructor
public class BookingPolicies {
    private final JdbcTemplate sql;
    private final CatalogAccess access;
    private final CatalogIdempotency idempotency;
    private final ResourceCatalog catalog;
    private final ObjectMapper json;
    private final Validator validator;
    private final Environment environment;

    public record Active(int version, PolicySettings settings) {
    }

    public void mode(String mode) {
        if (!java.util.Set.of("REAL", "DEMO").contains(mode))
            throw new BusinessException(400, "VALIDATION_ERROR", "需要明确指定 REAL 或 DEMO");
        if ("DEMO".equals(mode) && !environment.acceptsProfiles(Profiles.of("dev", "test")))
            throw new BusinessException(403, "FORBIDDEN", "演示数据仅允许在 dev/test 环境使用");
    }

    public Instant now() {
        return sql.queryForObject("SELECT UTC_TIMESTAMP(3)", Timestamp.class).toInstant();
    }

    public Active active(String scope) {
        mode(scope);
        var rows = sql.queryForList("SELECT policy_version,settings FROM booking_policy WHERE scope_code=? AND active=1", scope);
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        return new Active(((Number) row.get("policy_version")).intValue(),
                json.readValue(row.get("settings").toString(), PolicySettings.class));
    }

    public Map<String, Object> get(AuthActor actor, String scope) {
        access.administrator(actor);
        mode(scope);
        var rows = sql.queryForList("SELECT id,policy_version,settings,basis FROM booking_policy WHERE scope_code=? AND active=1", scope);
        if (rows.isEmpty()) return Map.of("scopeCode", scope, "configured", false, "version", 0);
        var row = rows.getFirst();
        return Map.of("scopeCode", scope, "configured", true, "version", row.get("policy_version"),
                "settings", json.readValue(row.get("settings").toString(), Map.class), "basis", row.get("basis"));
    }

    public CatalogIdempotency.Result create(AuthActor actor, String key, String scope, PolicyVersion input) {
        access.systemAdministrator(actor);
        mode(scope);
        if (!validator.validate(input).isEmpty())
            throw new BusinessException(400, "VALIDATION_ERROR", "策略值或依据不符合要求");
        return idempotency.execute(actor, "POLICY_VERSION:" + scope, key, input, () -> {
            // 先锁稳定的范围行；即使尚无版本，两次首次创建也会串行。
            sql.queryForObject("SELECT scope_code FROM booking_policy_scope WHERE scope_code=? FOR UPDATE", String.class, scope);
            int version = sql.queryForObject("SELECT COALESCE(MAX(policy_version),0) FROM booking_policy WHERE scope_code=?", Integer.class, scope);
            if (version != input.expectedVersion())
                throw new BusinessException(409, "VERSION_CONFLICT", "策略版本已变化，请刷新");
            sql.update("UPDATE booking_policy SET active=0 WHERE scope_code=? AND active=1", scope);
            sql.update("INSERT INTO booking_policy(scope_code,policy_version,settings,basis,active) VALUES (?,?,?,?,1)",
                    scope, version + 1, json.writeValueAsString(input.settings()), input.basis());
            long id = sql.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            catalog.audit(actor, "POLICY_VERSION", "BOOKING_POLICY", id, null, "ACTIVE");
            return new CatalogIdempotency.Result(201, get(actor, scope), false);
        });
    }

    public void eligible(TimeWindow window, Active policy, Instant now) {
        window.singleDay();
        var settings = policy.settings();
        if (window.start().isBefore(now.plusSeconds(settings.bookingLeadMinutes() * 60L))
                || window.start().isAfter(now.plusSeconds(settings.maxBookingDaysAhead() * 86400L))
                || java.time.Duration.between(window.start(), window.end()).compareTo(java.time.Duration.ofMinutes(settings.maxDurationMinutes())) > 0)
            throw new BusinessException(400, "TIME_RANGE_INVALID", "查询不符合提前量、最远日期或最大时长策略");
    }
}
