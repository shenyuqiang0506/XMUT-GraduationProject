package org.shen.graduationproject.infrastructure;

import java.time.OffsetDateTime;
import java.util.*;

import lombok.RequiredArgsConstructor;
import org.shen.graduationproject.domain.TimeWindow;
import org.shen.platform.support.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("business")
@RequiredArgsConstructor
public class TemporalFileParser {
    private final CatalogFileParser files;

    public record Entry(int rowNumber, String sourceId, String sourceKey, TimeWindow window, String reason) {
    }

    public record Parsed(int sourceRows, List<Entry> rows, List<CatalogFileParser.Error> errors,
                         Map<String, String> mapping) {
    }

    private static final Map<String, List<String>> ALIASES = Map.of(
            "sourceId", List.of("sourceId", "资源来源编号", "场地编号", "cdbh", "cd_id"),
            "sourceKey", List.of("sourceKey", "占用编号"),
            "startAt", List.of("startAt", "开始时间"), "endAt", List.of("endAt", "结束时间"),
            "reasonCode", List.of("reasonCode", "原因代码"));

    public Parsed parse(byte[] bytes, String key, String type, Map<String, String> override) {
        boolean occupancy = "EXTERNAL_OCCUPANCY".equals(type);
        var sourceRows = files.readRows(bytes, key, occupancy);
        if (!ALIASES.keySet().containsAll(override.keySet()) || override.values().stream().anyMatch(v -> v == null || v.length() > 100
                || v.toLowerCase(Locale.ROOT).matches(".*(password|token|cookie|authorization|密码|口令).*")))
            throw new BusinessException(400, "INVALID_FIELD_MAPPING", "时间数据映射包含无效或敏感字段");
        Set<String> columns = new HashSet<>();
        sourceRows.forEach(r -> columns.addAll(r.values().keySet()));
        var mapping = new TreeMap<String, String>();
        ALIASES.forEach((field, aliases) -> {
            String chosen = override.get(field);
            if (chosen == null) chosen = aliases.stream().filter(columns::contains).findFirst().orElse(null);
            if (chosen != null) mapping.put(field, chosen);
        });
        var rows = new ArrayList<Entry>();
        var errors = new ArrayList<CatalogFileParser.Error>();
        Set<String> unique = new HashSet<>();
        for (var source : sourceRows) {
            try {
                Object rawId = source.values().get(mapping.get("sourceId"));
                if (!(rawId instanceof String sourceId) || sourceId.isBlank() || sourceId.length() > 64)
                    throw new BusinessException(400, "MISSING_SOURCE_ID", "资源来源编号必须是非空字符串");
                sourceId = sourceId.strip();
                if (!override.containsKey("sourceId") && ALIASES.get("sourceId").stream().map(source.values()::get)
                        .filter(Objects::nonNull).map(Object::toString).map(String::strip).filter(s -> !s.isEmpty()).distinct().count() > 1)
                    throw new BusinessException(400, "SOURCE_ID_CONFLICT", "来源编号不一致，请指定字段映射");
                String sourceKey = text(source.values().get(mapping.get("sourceKey")));
                String reason = text(source.values().get(mapping.get("reasonCode")));
                if (occupancy && (sourceKey == null || sourceKey.length() > 128 || sourceKey.startsWith("__fragment_")
                        || !Set.of("COURSE", "EXAM", "MAINTENANCE", "OTHER").contains(reason == null ? "" : reason)))
                    throw new BusinessException(400, "VALIDATION_ERROR", "占用需要合法的来源编号和原因代码");
                var window = TimeWindow.of(OffsetDateTime.parse(text(source.values().get(mapping.get("startAt")))),
                        OffsetDateTime.parse(text(source.values().get(mapping.get("endAt")))));
                window.limitDays(31);
                String identity = sourceId + "\u0000" + (occupancy ? sourceKey : window.toString());
                if (!unique.add(identity))
                    throw new BusinessException(400, "DUPLICATE_SOURCE_KEY", "文件中的来源占用或空闲区间重复");
                rows.add(new Entry(source.row(), sourceId, sourceKey, window, reason));
            } catch (BusinessException invalid) {
                errors.add(new CatalogFileParser.Error(source.row(), "interval", invalid.code(), invalid.getMessage()));
            } catch (RuntimeException invalid) {
                errors.add(new CatalogFileParser.Error(source.row(), "interval", "TIME_RANGE_INVALID", "起止时间需要带偏移的 ISO 8601 字符串"));
            }
        }
        return new Parsed(sourceRows.size(), rows, errors, mapping);
    }

    private static String text(Object value) {
        return value instanceof String s && !s.isBlank() ? s.strip() : null;
    }
}
