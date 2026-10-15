package org.shen.ai.application;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.shen.ai.domain.*;
import org.shen.ai.infrastructure.*;
import org.shen.platform.contracts.AuthActor;
import org.shen.platform.support.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

/**
 * 受控的 AI 查询编排：认领→提取→校验→真实只读查询→短事务保存。
 * 每条消息最多一次模型调用、四次业务工具调用，总处理预算十秒，服务最多八条并行任务。
 * 模型不能构造 resourceIds；方案完全由已授权业务查询生成，创建预约仍由用户显式提交。
 */
@Service
@Profile("business")
@RequiredArgsConstructor
public class AiConversations {
    public record Sent(Map<String, Object> data, boolean replayed) {
    }

    private final AiRepository repo;
    private final ConditionExtractor model;
    private final ConditionNormalizer normalizer;
    private final BookingReadTools tools;
    private final ExecutorService workers = new ThreadPoolExecutor(0, 8, 30, TimeUnit.SECONDS, new SynchronousQueue<>(),
            runnable -> {
                var thread = new Thread(runnable, "ai-query");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    @PreDestroy
    void stop() {
        workers.shutdownNow();
    }

    public Sent send(AuthActor actor, long conversation, AiCommands.Send request, String token, String trace) {
        AiRepository.applicant(actor);
        var claim = repo.claim(actor, conversation, request, trace, model.modelName());
        if (claim.replay() != null) return new Sent(visible(claim.replay(), token, trace), true);
        Future<Map<String, Object>> task = null;
        Map<String, Object> outcome;
        BusinessException denied = null;
        try {
            task = workers.submit(() -> process(actor, claim, request.text(), token, trace));
            outcome = task.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            if (task != null) task.cancel(true);
            outcome = AiRepository.failure(claim.conditions(), "AI_PROCESSING_TIMEOUT");
        } catch (RejectedExecutionException overloaded) {
            outcome = AiRepository.failure(claim.conditions(), "AI_SERVICE_BUSY");
        } catch (InterruptedException interrupted) {
            if (task != null) task.cancel(true);
            Thread.currentThread().interrupt();
            outcome = AiRepository.failure(claim.conditions(), "AI_PROCESSING_INTERRUPTED");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof BusinessException security && Set.of(401, 403).contains(security.status()))
                denied = security;
            outcome = AiRepository.failure(claim.conditions(), denied == null ? "AI_QUERY_FAILED" : denied.code());
        }
        var saved = repo.finish(actor, claim, outcome);
        if (denied != null) throw denied;
        return new Sent(saved, false);
    }

    private Map<String, Object> process(AuthActor actor, AiRepository.Claim claim, String text, String token, String trace) {
        Map<String, Object> conditions = claim.conditions();
        try {
            var options = map(call(claim, "getVisibleOptions", Map.of("dataMode", claim.mode()), trace, () -> tools.options(claim.mode(), token, trace)).data());
            var extracted = model.extract(text, conditions, claim.history(), options, claim.now().atZone(ConditionNormalizer.ZONE).toLocalDate());
            checkInterrupted();
            var normalized = normalizer.apply(conditions, extracted, text, claim.now().atZone(ConditionNormalizer.ZONE).toLocalDate(), options);
            conditions = normalized.conditions();
            repo.checkpoint(claim, conditions);
            if (!normalized.missingFields().isEmpty()) {
                tools.verify(actor, token);
                return response("NEED_CLARIFICATION", conditions, normalized.missingFields(), List.of(), null,
                        "请补充或核对：" + String.join("、", normalized.missingFields().stream().map(AiConversations::label).toList()) + "。未明确的时间和人数不会自动填写。");
            }
            final Map<String, Object> accepted = conditions;
            var rules = map(call(claim, "getBookingRules", Map.of("dataMode", claim.mode()), trace, () -> tools.rules(claim.mode(), token, trace)).data());
            if (!Boolean.TRUE.equals(rules.get("configured")))
                return AiRepository.failure(conditions, "POLICY_NOT_CONFIGURED");
            var found = map(call(claim, "searchAvailableResources", accepted, trace, () -> tools.search(accepted, claim.mode(), token, trace)).data());
            var plans = maps(found.get("items"));
            if (plans.size() > 3) throw new AiFailure("AI_UNTRUSTED_TOOL_RESULT");
            if (plans.isEmpty()) {
                tools.verify(actor, token);
                var result = response("NO_MATCH", conditions, List.of(), List.of(), found.get("checkedAt"),
                        "未找到满足全部条件的组合。可调整时间、人数或设备；来源覆盖未知不能视为空闲。");
                result.put("reasonCodes", found.getOrDefault("reasonCodes", List.of("NO_MATCH")));
                return result;
            }
            var ids = planIds(plans);
            var details = maps(call(claim, "getResourceDetails", Map.of("resourceIds", ids), trace, () -> tools.details(ids, token, trace)).data());
            Map<String, Map<String, Object>> byId = new HashMap<>();
            details.forEach(row -> byId.put(row.get("id").toString(), row));
            if (!byId.keySet().equals(new HashSet<>(ids))) throw new AiFailure("AI_UNTRUSTED_TOOL_RESULT");
            var cards = new ArrayList<Map<String, Object>>();
            for (var plan : plans) {
                if (!"AVAILABLE".equals(plan.get("availability")) || !"COMPLETE".equals(plan.get("coverageStatus")) || !claim.mode().equals(plan.get("dataMode")))
                    throw new AiFailure("AI_UNTRUSTED_TOOL_RESULT");
                // 规则与搜索是独立只读请求；管理员恰在两次请求之间更新策略时不能混用旧依据与新版本。
                if (!(rules.get("version") instanceof Number ruleVersion) || !(plan.get("policyVersion") instanceof Number planVersion)
                        || ruleVersion.longValue() != planVersion.longValue()) throw new AiFailure("RULES_CHANGED");
                var card = new LinkedHashMap<>(plan);
                var members = new ArrayList<Map<String, Object>>();
                for (String id : strings(plan.get("resourceIds"))) {
                    var detail = byId.get(id);
                    if (!claim.mode().equals(detail.get("dataMode")) || !Objects.equals(plan.get("approvalGroupId"), detail.get("approvalGroupId")))
                        throw new AiFailure("AI_UNTRUSTED_TOOL_RESULT");
                    var summary = new LinkedHashMap<String, Object>();
                    for (String field : List.of("id", "name", "resourceType", "campus", "equipmentType"))
                        summary.put(field, detail.get(field));
                    members.add(summary);
                }
                card.put("resources", members);
                card.put("startAt", conditions.get("startAt"));
                card.put("endAt", conditions.get("endAt"));
                card.put("participantCount", conditions.get("participantCount"));
                card.put("requiredFeatures", conditions.getOrDefault("requiredFeatures", List.of()));
                card.put("ruleBasis", rules.get("basis"));
                cards.add(card);
            }
            checkInterrupted();
            tools.verify(actor, token);
            return response("READY", conditions, List.of(), cards, found.get("checkedAt"), "已找到可核查方案。请核对条件并填写活动信息；提交时会重新检查资源，之后仍需审批。");
        } catch (AiFailure failure) {
            return AiRepository.failure(conditions, failure.code());
        } catch (BusinessException failure) {
            if (Set.of(401, 403).contains(failure.status())) throw failure;
            if ("TIME_RANGE_INVALID".equals(failure.code()))
                return response("NEED_CLARIFICATION", conditions, List.of("timeRange"), List.of(), null,
                        "时间条件不符合当前预约规则，请核对提前量、最远日期及最大时长后修改。");
            return AiRepository.failure(conditions, failure.code());
        } catch (RestClientResponseException failure) {
            if (Set.of(401, 403).contains(failure.getStatusCode().value()))
                throw new BusinessException(failure.getStatusCode().value(), "SESSION_REVOKED", "登录状态已失效");
            return AiRepository.failure(conditions, "SERVICE_UNAVAILABLE");
        }
    }

    private BookingReadTools.Reply call(AiRepository.Claim claim, String name, Object input, String trace, Supplier<BookingReadTools.Reply> request) {
        checkInterrupted();
        long start = System.nanoTime();
        try {
            var reply = request.get();
            checkInterrupted();
            var summary = new LinkedHashMap<String, Object>();
            summary.put("received", true);
            if (reply.data() instanceof Map<?, ?> map && map.get("items") instanceof List<?> items)
                summary.put("itemCount", items.size());
            if (reply.data() instanceof List<?> items) summary.put("itemCount", items.size());
            repo.tool(claim, name, input, "SUCCEEDED", summary, (System.nanoTime() - start) / 1000000, reply.traceId());
            return reply;
        } catch (RuntimeException failure) {
            String code = failure instanceof BusinessException business ? business.code() : failure instanceof AiFailure ai ? ai.code() : "SERVICE_UNAVAILABLE";
            repo.tool(claim, name, input, "FAILED", Map.of("errorCode", code), (System.nanoTime() - start) / 1000000, trace);
            throw failure;
        }
    }

    public Map<String, Object> history(AuthActor actor, long id, String token, String trace) {
        var result = new LinkedHashMap<>(repo.history(actor, id));
        result.put("latest", visible(map(result.get("latest")), token, trace));
        return result;
    }

    /**
     * 历史方案及幂等回放仍检查当前可见性，不能借旧对话读取后来隐藏的资源。
     */
    private Map<String, Object> visible(Map<String, Object> result, String token, String trace) {
        if (!"READY".equals(result.get("status"))) return result;
        try {
            tools.details(planIds(maps(result.get("plans"))), token, trace);
            return result;
        } catch (BusinessException unavailable) {
            if (Set.of(401, 403).contains(unavailable.status())) throw unavailable;
            var redacted = new LinkedHashMap<>(result);
            redacted.put("plans", List.of());
            redacted.put("status", "FAILED");
            redacted.put("errorCode", "PLAN_RECHECK_REQUIRED");
            redacted.put("reply", "原方案当前无法复核，请保留条件并重新查询。");
            return redacted;
        }
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new AiFailure("AI_PROCESSING_INTERRUPTED");
    }

    private static Map<String, Object> response(String status, Map<String, Object> conditions, List<String> missing, List<?> plans, Object checked, String reply) {
        var out = new LinkedHashMap<String, Object>();
        out.put("status", status);
        out.put("conditions", conditions);
        out.put("missingFields", missing);
        out.put("plans", plans);
        out.put("checkedAt", checked);
        out.put("reply", reply);
        return out;
    }

    private static String label(String field) {
        return switch (field) {
            case "date" -> "日期";
            case "startLocal" -> "开始时刻";
            case "endLocalOrDuration" -> "结束时刻或时长";
            case "participantCount" -> "人数";
            case "campus" -> "目录中的校区";
            case "equipmentRequirements" -> "设备类型与数量";
            default -> "同一上海自然日内的起止时间";
        };
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new AiFailure("AI_UNTRUSTED_TOOL_RESULT");
        return (Map<String, Object>) value;
    }

    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> values) || values.stream().anyMatch(v -> !(v instanceof Map<?, ?>)))
            throw new AiFailure("AI_UNTRUSTED_TOOL_RESULT");
        return (List<Map<String, Object>>) value;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> values) || values.stream().anyMatch(v -> !(v instanceof String s) || !s.matches("[1-9][0-9]{0,18}")))
            throw new AiFailure("AI_UNTRUSTED_TOOL_RESULT");
        return (List<String>) value;
    }

    private static List<String> planIds(List<Map<String, Object>> plans) {
        var ids = new TreeSet<String>(Comparator.comparingLong(Long::parseLong));
        for (var plan : plans) {
            List<String> members = strings(plan.get("resourceIds")), equipment = strings(plan.get("equipmentIds"));
            String venue = String.valueOf(plan.get("venueId"));
            var expected = new HashSet<>(equipment);
            expected.add(venue);
            if (!new HashSet<>(members).equals(expected) || members.size() != expected.size() || equipment.size() + 1 != members.size())
                throw new AiFailure("AI_UNTRUSTED_TOOL_RESULT");
            ids.addAll(members);
        }
        if (ids.isEmpty() || ids.size() > 128) throw new AiFailure("AI_UNTRUSTED_TOOL_RESULT");
        return List.copyOf(ids);
    }
}
