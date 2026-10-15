package org.shen.platform.contracts;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 预约与候补通知的固定消息契约；不携带访问令牌、密码、原始导入数据或模型密钥。
 */
public record BookingEvent(String eventId, String eventType, String aggregateType, String aggregateId,
                           Integer aggregateVersion,
                           String occurredAt, List<String> recipientIds, Map<String, Object> payload, String traceId,
                           String deliveryScope) {
    private static final Map<String, String> STATES = Map.ofEntries(
            Map.entry("APPLICATION_SUBMITTED", "PENDING_APPROVAL"), Map.entry("APPLICATION_APPROVED", "APPROVED"),
            Map.entry("APPLICATION_REJECTED", "REJECTED"), Map.entry("APPLICATION_CANCELLED", "CANCELLED"),
            Map.entry("APPLICATION_EXPIRED", "EXPIRED"), Map.entry("APPLICATION_COMPLETED", "COMPLETED"),
            Map.entry("APPLICATION_SOURCE_CONFLICT", "CONFLICT_REVIEW"), Map.entry("WAITLIST_CREATED", "WAITING"),
            Map.entry("WAITLIST_OFFERED", "OFFERED"), Map.entry("WAITLIST_CONFIRMED", "CONFIRMED"),
            Map.entry("WAITLIST_EXPIRED", "EXPIRED"), Map.entry("WAITLIST_CANCELLED", "CANCELLED"));

    public void validate(String expectedScope) {
        if (eventId == null || !eventId.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                || eventType == null || !STATES.containsKey(eventType) || aggregateType == null
                || !("APPLICATION".equals(aggregateType) || "WAITLIST".equals(aggregateType)) || !eventType.startsWith(aggregateType + "_")
                || aggregateId == null || !aggregateId.matches("[1-9][0-9]{0,18}") || aggregateVersion == null || aggregateVersion < 0 || !expectedScope.equals(deliveryScope)
                || recipientIds == null || recipientIds.isEmpty() || recipientIds.size() > 201 || new HashSet<>(recipientIds).size() != recipientIds.size()
                || recipientIds.stream().anyMatch(id -> id == null || !id.matches("[1-9][0-9]{0,18}"))
                || payload == null || !STATES.get(eventType).equals(payload.get("status"))
                || traceId == null || !traceId.matches("[A-Za-z0-9_-]{8,64}"))
            throw new IllegalArgumentException("预约事件契约无效");
        if ("WAITLIST_OFFERED".equals(eventType) && payload.get("offerExpiresAt") == null)
            throw new IllegalArgumentException("候补 offer 缺少期限");
        if ("WAITLIST_CONFIRMED".equals(eventType) && payload.get("applicationId") == null)
            throw new IllegalArgumentException("候补确认缺少关联申请");
        // 数字必须落在 MySQL BIGINT 正值范围，不能仅通过字符串长度检查。
        Long.parseLong(aggregateId);
        recipientIds.forEach(Long::parseLong);
        OffsetDateTime.parse(occurredAt);
        if (payload.get("reason") != null && (!(payload.get("reason") instanceof String reason) || reason.length() > 500))
            throw new IllegalArgumentException("事件理由无效");
        if (!Set.of("status", "reason", "offerExpiresAt", "applicationId").containsAll(payload.keySet()))
            throw new IllegalArgumentException("事件包含未知业务字段");
        if (payload.get("offerExpiresAt") != null) OffsetDateTime.parse(payload.get("offerExpiresAt").toString());
        if (payload.get("applicationId") != null) {
            String id = payload.get("applicationId").toString();
            if (!id.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("关联申请编号无效");
            Long.parseLong(id);
        }
    }
}
