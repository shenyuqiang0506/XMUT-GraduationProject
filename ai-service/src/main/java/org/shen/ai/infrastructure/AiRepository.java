package org.shen.ai.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

import org.shen.ai.domain.*;
import org.shen.platform.contracts.AuthActor;
import org.shen.platform.support.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * AI 分库状态边界。锁顺序为用户预算行→本人会话行，远程调用在认领事务提交后才发生。
 * 重复消息先比较请求摘要并回放保存结果，不重复增加预算；短租约用于重启和迟到任务恢复。
 */
@Repository
@Profile("business")
public class AiRepository {
    public record Created(Map<String, Object> data, boolean replayed) {
    }

    public record Claim(long conversationId, long messageId, String token, String mode, int version,
                        Map<String, Object> conditions, List<Map<String, Object>> history, Instant now,
                        Map<String, Object> replay) {
    }

    private final JdbcTemplate sql;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Environment environment;
    private final int hourlyLimit;

    public AiRepository(JdbcTemplate sql, TransactionTemplate tx, ObjectMapper json, Environment environment,
                        @Value("${AI_HOURLY_LIMIT:20}") int hourlyLimit) {
        this.sql = sql;
        this.tx = tx;
        this.json = json;
        this.environment = environment;
        this.hourlyLimit = hourlyLimit;
        if (hourlyLimit < 1 || hourlyLimit > 1000) throw new IllegalStateException("AI 小时调用上限需为 1 至 1000");
    }

    public static void applicant(AuthActor actor) {
        if (actor.roles().stream().noneMatch(r -> r.equals("STUDENT") || r.equals("TEACHER")))
            throw new BusinessException(403, "FORBIDDEN", "智能查询仅面向学生和教师账号");
    }

    private void mode(String mode) {
        if (!Set.of("REAL", "DEMO").contains(mode))
            throw new BusinessException(400, "VALIDATION_ERROR", "需要明确数据模式");
        if (mode.equals("DEMO") && (environment.acceptsProfiles(Profiles.of("prod")) || !environment.acceptsProfiles(Profiles.of("dev", "test"))))
            throw new BusinessException(403, "FORBIDDEN", "DEMO 仅用于 dev/test 隔离验收");
    }

    private Instant now() {
        return sql.queryForObject("SELECT UTC_TIMESTAMP(3)", Timestamp.class).toInstant();
    }

    /**
     * Connector/J 的 DATETIME 默认可能返回 LocalDateTime；会话库连接固定 UTC，不能按本机时区解释。
     */
    private static Instant instant(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof LocalDateTime local) return local.toInstant(ZoneOffset.UTC);
        if (value instanceof Instant instant) return instant;
        throw new IllegalStateException("AI 数据库时间格式无效");
    }

    private Map<String, Object> conditions(Map<String, Object> row) {
        return new LinkedHashMap<>(json.readValue(row.get("normalized_conditions").toString(), Map.class));
    }

    private Map<String, Object> owned(AuthActor actor, long id, boolean lock) {
        applicant(actor);
        var rows = sql.queryForList("SELECT * FROM ai_conversation WHERE id=? AND owner_id=?" + (lock ? " FOR UPDATE" : ""), id, Long.parseLong(actor.userId()));
        if (rows.isEmpty()) throw new BusinessException(404, "NOT_FOUND", "会话不存在或不可见");
        mode(rows.getFirst().get("data_mode").toString());
        return rows.getFirst();
    }

    private Map<String, Object> view(Map<String, Object> row) {
        return Map.of("conversationId", row.get("id").toString(), "dataMode", row.get("data_mode"), "status", row.get("status"),
                "version", row.get("condition_version"), "conditions", conditions(row), "processing", row.get("processing_token") != null);
    }

    private void budgetLock(long uid) {
        sql.update("INSERT INTO ai_user_budget(owner_id,window_start) VALUES (?,UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE owner_id=owner_id", uid);
        sql.queryForMap("SELECT * FROM ai_user_budget WHERE owner_id=? FOR UPDATE", uid);
    }

    public Created create(AuthActor actor, String key, String mode) {
        applicant(actor);
        mode(mode);
        if (key == null || !key.matches("[A-Za-z0-9_-]{8,64}"))
            throw new BusinessException(400, "INVALID_IDEMPOTENCY_KEY", "需要有效幂等键");
        return tx.execute(status -> {
            long uid = Long.parseLong(actor.userId());
            budgetLock(uid);
            var prior = sql.queryForList("SELECT * FROM ai_conversation WHERE owner_id=? AND create_key=?", uid, key);
            if (!prior.isEmpty()) {
                if (!mode.equals(prior.getFirst().get("data_mode")))
                    throw new BusinessException(409, "IDEMPOTENCY_KEY_REUSED", "同一请求键的数据模式不同");
                return new Created(view(prior.getFirst()), true);
            }
            if (sql.queryForObject("SELECT COUNT(*) FROM ai_conversation WHERE owner_id=? AND status='ACTIVE'", Long.class, uid) >= 20)
                throw new BusinessException(422, "AI_CONVERSATION_LIMIT", "请先关闭不再使用的会话");
            sql.update("INSERT INTO ai_conversation(owner_id,create_key,data_mode,normalized_conditions) VALUES (?,?,?,?)", uid, key, mode,
                    json.writeValueAsString(Map.of("requiredFeatures", List.of(), "equipmentRequirements", List.of())));
            long id = sql.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            return new Created(view(owned(actor, id, false)), false);
        });
    }

    public Map<String, Object> list(AuthActor actor) {
        applicant(actor);
        String modes = environment.acceptsProfiles(Profiles.of("prod")) ? " AND data_mode='REAL'" : "";
        var rows = sql.queryForList("SELECT * FROM ai_conversation WHERE owner_id=? AND status='ACTIVE'" + modes + " ORDER BY updated_at DESC,id DESC LIMIT 20", Long.parseLong(actor.userId()));
        return Map.of("items", rows.stream().map(this::view).toList());
    }

    private Map<String, Object> saved(long message) {
        var row = sql.queryForMap("SELECT result_json,status FROM ai_message WHERE id=?", message);
        if (row.get("result_json") == null)
            throw new BusinessException(409, "AI_MESSAGE_PROCESSING", "该消息仍在处理，请稍后读取会话");
        return json.readValue(row.get("result_json").toString(), Map.class);
    }

    /**
     * 租约过期只标记失败并清除认领，不重放可能已收费的模型调用。
     */
    private void recover(Map<String, Object> row) {
        if (row.get("processing_token") == null || instant(row.get("processing_until")).isAfter(now())) return;
        var messages = sql.queryForList("SELECT id,accepted_conditions FROM ai_message WHERE conversation_id=? AND claim_token=? AND status='PROCESSING'", row.get("id"), row.get("processing_token"));
        for (var message : messages) {
            long id = ((Number) message.get("id")).longValue();
            Map<String, Object> accepted = message.get("accepted_conditions") == null ? conditions(row) : json.readValue(message.get("accepted_conditions").toString(), Map.class);
            int version = ((Number) row.get("condition_version")).intValue() + 1;
            var result = failure(accepted, "AI_PROCESSING_EXPIRED");
            result.put("messageId", Long.toString(id));
            result.put("conditionVersion", version);
            sql.update("UPDATE ai_message SET status='FAILED',condition_version=?,result_json=? WHERE id=? AND status='PROCESSING'", version, json.writeValueAsString(result), id);
            sql.update("UPDATE ai_conversation SET normalized_conditions=?,condition_version=? WHERE id=?", json.writeValueAsString(accepted), version, row.get("id"));
            row.put("condition_version", version);
            row.put("normalized_conditions", json.writeValueAsString(accepted));
        }
        sql.update("UPDATE ai_conversation SET processing_token=NULL,processing_until=NULL WHERE id=?", row.get("id"));
        row.put("processing_token", null);
        row.put("processing_until", null);
    }

    public Claim claim(AuthActor actor, long id, AiCommands.Send input, String trace, String model) {
        return tx.execute(status -> {
            long uid = Long.parseLong(actor.userId());
            budgetLock(uid);
            var row = owned(actor, id, true);
            recover(row);
            String hash = hash(json.writeValueAsString(input));
            var prior = sql.queryForList("SELECT id,request_hash FROM ai_message WHERE conversation_id=? AND client_message_id=?", id, input.clientMessageId());
            if (!prior.isEmpty()) {
                if (!hash.equals(prior.getFirst().get("request_hash")))
                    throw new BusinessException(409, "AI_MESSAGE_ID_REUSED", "消息编号对应的文本或版本不同");
                return new Claim(id, ((Number) prior.getFirst().get("id")).longValue(), "", row.get("data_mode").toString(), 0, Map.of(), List.of(), now(), saved(((Number) prior.getFirst().get("id")).longValue()));
            }
            if (!"ACTIVE".equals(row.get("status")))
                throw new BusinessException(409, "AI_CONVERSATION_CLOSED", "会话已经关闭");
            int version = ((Number) row.get("condition_version")).intValue();
            if (version != input.expectedConditionVersion())
                throw new BusinessException(409, "VERSION_CONFLICT", "会话条件已更新，请重新读取");
            if (row.get("processing_token") != null)
                throw new BusinessException(409, "AI_CONVERSATION_BUSY", "当前会话已有消息在处理");
            if (sql.queryForObject("SELECT COUNT(*) FROM ai_message WHERE conversation_id=?", Long.class, id) >= 40)
                throw new BusinessException(422, "AI_MESSAGE_LIMIT", "该会话消息过多，请建立新会话");
            Instant now = now();
            var budget = sql.queryForMap("SELECT * FROM ai_user_budget WHERE owner_id=?", uid);
            if (!instant(budget.get("window_start")).plusSeconds(3600).isAfter(now)) {
                sql.update("UPDATE ai_user_budget SET window_start=?,requests=0 WHERE owner_id=?", Timestamp.from(now), uid);
                budget.put("requests", 0);
            }
            if (((Number) budget.get("requests")).intValue() >= hourlyLimit)
                throw new BusinessException(429, "AI_RATE_LIMITED", "智能查询次数较多，请稍后使用普通查询");
            sql.update("UPDATE ai_user_budget SET requests=requests+1 WHERE owner_id=?", uid);
            String token = UUID.randomUUID().toString();
            sql.update("INSERT INTO ai_message(conversation_id,client_message_id,request_hash,content,base_version,status,claim_token,prompt_version,model_name,trace_id) VALUES (?,?,?,?,?,'PROCESSING',?,?,?,?)",
                    id, input.clientMessageId(), hash, input.text(), version, token, ConditionExtractor.PROMPT_VERSION, model, trace);
            long message = sql.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            sql.update("UPDATE ai_conversation SET processing_token=?,processing_until=? WHERE id=?", token, Timestamp.from(now.plusSeconds(20)), id);
            var history = sql.queryForList("SELECT content,result_json FROM ai_message WHERE conversation_id=? AND id<? ORDER BY id DESC LIMIT 4", id, message);
            var context = new ArrayList<Map<String, Object>>();
            for (var old : history)
                context.add(Map.of("text", old.get("content"), "reply", old.get("result_json") == null ? "" : json.readValue(old.get("result_json").toString(), Map.class).getOrDefault("reply", "")));
            Collections.reverse(context);
            return new Claim(id, message, token, row.get("data_mode").toString(), version, conditions(row), context, now, null);
        });
    }

    public Map<String, Object> finish(AuthActor actor, Claim claim, Map<String, Object> result) {
        return tx.execute(status -> {
            var row = owned(actor, claim.conversationId(), true);
            recover(row);
            if (!claim.token().equals(row.get("processing_token")) || ((Number) row.get("condition_version")).intValue() != claim.version())
                return saved(claim.messageId());
            int version = claim.version() + 1;
            var response = new LinkedHashMap<>(result);
            response.put("messageId", Long.toString(claim.messageId()));
            response.put("conditionVersion", version);
            // 总预算超时或线程异常也保留已经通过证据校验的条件，不退回消息认领时的旧条件。
            if ("FAILED".equals(response.get("status"))) {
                String accepted = sql.queryForObject("SELECT accepted_conditions FROM ai_message WHERE id=?", String.class, claim.messageId());
                if (accepted != null) response.put("conditions", json.readValue(accepted, Map.class));
            }
            sql.update("UPDATE ai_message SET status=?,condition_version=?,result_json=? WHERE id=? AND claim_token=? AND status='PROCESSING'", response.get("status"), version, json.writeValueAsString(response), claim.messageId(), claim.token());
            sql.update("UPDATE ai_conversation SET normalized_conditions=?,condition_version=?,processing_token=NULL,processing_until=NULL WHERE id=?", json.writeValueAsString(response.get("conditions")), version, claim.conversationId());
            return response;
        });
    }

    public Map<String, Object> history(AuthActor actor, long id) {
        return tx.execute(status -> {
            var row = owned(actor, id, true);
            recover(row);
            var messages = sql.queryForList("SELECT id,client_message_id,content,status,result_json FROM ai_message WHERE conversation_id=? ORDER BY id DESC LIMIT 10", id);
            var items = new ArrayList<Map<String, Object>>();
            Map<String, Object> latest = Map.of();
            for (int i = 0; i < messages.size(); i++) {
                var message = messages.get(i);
                Map<String, Object> result = message.get("result_json") == null ? Map.of("status", "PROCESSING") : json.readValue(message.get("result_json").toString(), Map.class);
                if (i == 0) latest = result;
                // 旧消息只提供对话摘要；资源方案单独在应用层按当前权限重新读取后才返回。
                items.add(Map.of("messageId", message.get("id").toString(), "clientMessageId", message.get("client_message_id"), "text", message.get("content"), "status", message.get("status"), "reply", result.getOrDefault("reply", "正在处理…")));
            }
            Collections.reverse(items);
            return Map.of("conversation", view(row), "items", items, "latest", latest);
        });
    }

    public Map<String, Object> close(AuthActor actor, long id, int expected) {
        return tx.execute(status -> {
            var row = owned(actor, id, true);
            recover(row);
            if ("CLOSED".equals(row.get("status"))) return view(row);
            if (row.get("processing_token") != null)
                throw new BusinessException(409, "AI_CONVERSATION_BUSY", "请等待当前消息结束");
            if (((Number) row.get("condition_version")).intValue() != expected)
                throw new BusinessException(409, "VERSION_CONFLICT", "会话版本已更新");
            sql.update("UPDATE ai_conversation SET status='CLOSED',condition_version=condition_version+1 WHERE id=?", id);
            return view(owned(actor, id, false));
        });
    }

    public void tool(Claim claim, String name, Object arguments, String state, Map<String, Object> summary, long duration, String trace) {
        sql.update("INSERT INTO ai_tool_call(conversation_id,message_id,tool_name,input_arguments,result_summary,status,duration_ms,trace_id) SELECT conversation_id,id,?,?,?,?,?,? FROM ai_message WHERE id=? AND claim_token=? AND status='PROCESSING'",
                name, json.writeValueAsString(arguments), json.writeValueAsString(summary), state, (int) Math.min(Integer.MAX_VALUE, duration), trace, claim.messageId(), claim.token());
    }

    /**
     * 条件检查点与版本提交分开：只保留已校验的用户意图，不能产生 READY 或预约占用。
     */
    public void checkpoint(Claim claim, Map<String, Object> conditions) {
        sql.update("UPDATE ai_message SET accepted_conditions=? WHERE id=? AND claim_token=? AND status='PROCESSING'",
                json.writeValueAsString(conditions), claim.messageId(), claim.token());
    }

    public static Map<String, Object> failure(Map<String, Object> conditions, String code) {
        var out = new LinkedHashMap<String, Object>();
        out.put("status", "FAILED");
        out.put("conditions", conditions);
        out.put("missingFields", List.of());
        out.put("plans", List.of());
        out.put("checkedAt", null);
        out.put("errorCode", code);
        out.put("reply", switch (code) {
            case "AI_NOT_CONFIGURED" -> "智能查询暂未配置模型，请使用普通查询。已有条件已保留。";
            case "POLICY_NOT_CONFIGURED" -> "当前尚未配置已确认的预约规则，请联系管理员。已有条件已保留。";
            case "AI_CONTEXT_TOO_LARGE" -> "对话或目录选项较多，请缩小范围或改用普通查询。已有条件已保留。";
            default -> "本次智能查询未能完成，已保留条件。可重新发送消息，或改用普通查询。";
        });
        return out;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
