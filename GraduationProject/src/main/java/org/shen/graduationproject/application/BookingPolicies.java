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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 按 REAL、DEMO 分别管理预约策略版本及生效依据。
 * 没有生效策略时不补默认值；新版本在范围守卫锁下替换当前版本，旧版本保留。
 * 时间判断采用数据库 UTC 时钟，避免不同应用实例的本机时钟差影响规则判定。
 */
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
    private final DataModes dataModes;

    public record Active(int version, PolicySettings settings) {
    }

    /**
     * 校验数据模式并限制测试资料环境；部署环境 dev 与数据模式 REAL 是两个独立维度。
     */
    public void mode(String mode) {
        dataModes.check(mode);
    }

    /**
     * 取得数据库 UTC 毫秒时间，作为来源新鲜度和预约提前量共同的判断基准。
     */
    public Instant now() {
        return sql.queryForObject("SELECT UTC_TIMESTAMP(3)", Timestamp.class).toInstant();
    }

    /**
     * 读取指定模式的当前生效版本；没有配置时返回 null，由调用方明确处理而非套用默认规则。
     */
    public Active active(String scope) {
        mode(scope);
        var rows = sql.queryForList("SELECT policy_version,settings FROM booking_policy WHERE scope_code=? AND active=1", scope);
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        return new Active(((Number) row.get("policy_version")).intValue(),
                json.readValue(row.get("settings").toString(), PolicySettings.class));
    }

    /**
     * 向管理员返回生效策略或未配置状态；版本 0 代表尚未创建策略。
     */
    public Map<String, Object> get(AuthActor actor, String scope) {
        access.administrator(actor);
        mode(scope);
        var rows = sql.queryForList("SELECT id,policy_version,settings,basis FROM booking_policy WHERE scope_code=? AND active=1", scope);
        if (rows.isEmpty()) return Map.of("scopeCode", scope, "configured", false, "version", 0);
        var row = rows.getFirst();
        return Map.of("scopeCode", scope, "configured", true, "version", row.get("policy_version"),
                "settings", json.readValue(row.get("settings").toString(), Map.class), "basis", row.get("basis"));
    }

    /**
     * 普通申请人查询生效制度，不授予修改策略或管理员权限；仅返回当前模式公开规则。
     */
    public Map<String, Object> publicRules(AuthActor actor, String scope) {
        mode(scope);
        var rows = sql.queryForList("SELECT policy_version,settings,basis FROM booking_policy WHERE scope_code=? AND active=1", scope);
        if (rows.isEmpty()) return Map.of("scopeCode", scope, "configured", false, "version", 0);
        var row = rows.getFirst();
        return Map.of("scopeCode", scope, "configured", true, "version", row.get("policy_version"),
                "settings", json.readValue(row.get("settings").toString(), Map.class), "basis", row.get("basis"));
    }

    /**
     * 只允许系统管理员带版本和制度依据创建新策略。守卫行保证首次创建也有可锁对象。
     */
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

    /**
     * 约束上海自然日、最晚提前量、最远预约时间及活动最大时长；此方法不检查资源占用。
     */
    public void eligible(TimeWindow window, Active policy, Instant now) {
        window.singleDay();
        var settings = policy.settings();
        if (window.start().isBefore(now.plusSeconds(settings.bookingLeadMinutes() * 60L))
                || window.start().isAfter(now.plusSeconds(settings.maxBookingDaysAhead() * 86400L))
                || java.time.Duration.between(window.start(), window.end()).compareTo(java.time.Duration.ofMinutes(settings.maxDurationMinutes())) > 0)
            throw new BusinessException(400, "TIME_RANGE_INVALID", "查询不符合提前量、最远日期或最大时长策略");
    }
}
