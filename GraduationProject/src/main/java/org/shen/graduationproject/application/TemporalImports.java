package org.shen.graduationproject.application;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

import lombok.RequiredArgsConstructor;
import org.shen.graduationproject.domain.TimeWindow;
import org.shen.graduationproject.infrastructure.*;
import org.shen.platform.contracts.AuthActor;
import org.shen.platform.support.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

/**
 * 来源占用与已确认空闲区间的导入应用服务。
 * 完整快照必须声明资源、时间窗口、采集时间与来源依据；部分数据不能证明空闲。
 * 确认事务按资源 ID 升序锁定，再更新占用、覆盖、批次和审计，避免资源间出现半批成功。
 */
@Service
@Profile("business")
@RequiredArgsConstructor
public class TemporalImports {
    private final JdbcTemplate sql;
    private final CatalogAccess access;
    private final CatalogIdempotency idempotency;
    private final ResourceCatalog catalog;
    private final ImportStorage storage;
    private final TemporalFileParser parser;
    private final BookingPolicies policies;
    private final ObjectMapper json;

    /**
     * 解析行绑定到平台资源 ID 后的内部结构，后续写入不再以用户可变名称定位资源。
     */
    private record Bound(long resource, TemporalFileParser.Entry entry) {
    }

    /**
     * 本次来源证据的声明范围；有序资源集合同时用于稳定锁顺序。
     */
    private record Context(TimeWindow window, Instant captured, String completeness, SortedSet<Long> resources) {
    }

    private record Prepared(TemporalFileParser.Parsed parsed, List<Bound> rows, Context context,
                            List<CatalogFileParser.Error> errors) {
    }

    private Map<String, Object> object(String text, int maximum) {
        try {
            if (text.length() > maximum) throw new IllegalArgumentException();
            Object decoded = json.readValue(text, Object.class);
            if (!(decoded instanceof Map<?, ?>)) throw new IllegalArgumentException();
            return (Map<String, Object>) decoded;
        } catch (RuntimeException invalid) {
            throw new BusinessException(400, "VALIDATION_ERROR", "字段映射或查询条件需要有效 JSON 对象");
        }
    }

    private Map<String, String> mapping(Map<String, Object> input) {
        var result = new TreeMap<String, String>();
        input.forEach((k, v) -> {
            if (!(v instanceof String s))
                throw new BusinessException(400, "INVALID_FIELD_MAPPING", "映射值需要列名字符串");
            result.put(k, s);
        });
        return result;
    }

    private Map<String, Object> batch(AuthActor actor, long id, boolean lock) {
        access.administrator(actor);
        var rows = sql.queryForList("SELECT * FROM import_batch WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
        if (rows.isEmpty() || (!access.system(actor) && !actor.userId().equals(rows.getFirst().get("operator_id").toString())))
            throw new BusinessException(404, "NOT_FOUND", "导入批次不存在或不可见");
        return rows.getFirst();
    }

    private Map<String, Object> view(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        result.put("batchId", row.get("id").toString());
        result.put("fileHash", row.get("file_sha256"));
        result.put("filename", row.get("filename"));
        result.put("sourceSystem", row.get("source_system"));
        result.put("dataMode", row.get("data_mode"));
        result.put("importType", row.get("import_type"));
        result.put("status", row.get("status"));
        result.putAll(json.readValue(row.get("preview_result").toString(), Map.class));
        result.put("requiresConfirmation", !"COMMITTED".equals(row.get("status")));
        return result;
    }

    /**
     * 共同用于预览和确认的完整校验流程：绑定来源资源、验证权限、核对范围与采集时间。
     * 行错误保留原行号；不完整的上下文直接拒绝，不能据此生成完整覆盖。
     */
    private Prepared prepare(AuthActor actor, byte[] bytes, String file, String type, String source, String mode,
                             Map<String, String> mapping, Map<String, Object> context) {
        var parsed = parser.parse(bytes, file, type, mapping);
        var errors = new ArrayList<>(parsed.errors());
        var rows = new ArrayList<Bound>();
        var ids = new TreeSet<Long>();
        for (var entry : parsed.rows()) {
            try {
                Long resource = catalog.find(source, entry.sourceId(), mode);
                if (resource == null) throw new BusinessException(404, "NOT_FOUND", "来源资源不存在或不可见");
                access.manage(actor, resource);
                ids.add(resource);
                rows.add(new Bound(resource, entry));
            } catch (BusinessException invalid) {
                errors.add(new CatalogFileParser.Error(entry.rowNumber(), "sourceId", invalid.code(), invalid.getMessage()));
            }
        }
        if (!Set.of("from", "to", "capturedAt", "completeness", "sourceDescription", "resourceIds", "queryDate", "periods").containsAll(context.keySet()))
            throw new BusinessException(400, "VALIDATION_ERROR", "占用查询条件包含未知字段");
        // COMPLETE 必须由来源明确声明，不能因为文件没有占用行就自动判定完整。
        boolean complete = "COMPLETE".equals(context.get("completeness"));
        if (context.containsKey("completeness") && !(context.get("completeness") instanceof String value && Set.of("COMPLETE", "PARTIAL").contains(value)))
            throw new BusinessException(400, "VALIDATION_ERROR", "覆盖完整性需要 COMPLETE 或 PARTIAL");
        var declared = new TreeSet<Long>();
        if (context.containsKey("resourceIds")) {
            if (!(context.get("resourceIds") instanceof List<?> list) || list.isEmpty() || list.size() > 100)
                throw new BusinessException(400, "VALIDATION_ERROR", "明确资源范围需要 1–100 个字符串 ID");
            for (Object value : list) {
                if (!(value instanceof String s) || !declared.add(CatalogAccess.id(s)))
                    throw new BusinessException(400, "VALIDATION_ERROR", "资源范围不能重复且必须使用字符串 ID");
            }
            if (!declared.containsAll(ids))
                throw new BusinessException(400, "VALIDATION_ERROR", "文件资源超出声明范围");
            ids.addAll(declared);
        }
        // 用数据库时钟判断采集时间是否在未来；证据允许过期保存，但查询时会根据策略判为未知。
        Instant now = policies.now();
        TimeWindow window;
        Instant captured;
        try {
            if (complete && (declared.isEmpty() || !(context.get("sourceDescription") instanceof String description)
                    || description.isBlank() || description.length() > 500 || !context.containsKey("capturedAt")
                    || !context.containsKey("from") || !context.containsKey("to")))
                throw new IllegalArgumentException();
            if (context.containsKey("from") || context.containsKey("to"))
                window = TimeWindow.of(OffsetDateTime.parse((String) context.get("from")), OffsetDateTime.parse((String) context.get("to")));
            else {
                if (rows.isEmpty()) throw new IllegalArgumentException();
                window = new TimeWindow(rows.stream().map(r -> r.entry().window().start()).min(Instant::compareTo).orElseThrow(),
                        rows.stream().map(r -> r.entry().window().end()).max(Instant::compareTo).orElseThrow());
            }
            captured = context.containsKey("capturedAt") ? OffsetDateTime.parse((String) context.get("capturedAt")).toInstant() : now;
            if (captured.isAfter(now) || captured.getNano() % 1000000 != 0) throw new IllegalArgumentException();
            window.limitDays(31);
        } catch (RuntimeException invalid) {
            throw new BusinessException(400, "TIME_RANGE_INVALID", "完整覆盖需要明确资源、起止范围、来源说明及非未来的采集时间");
        }
        for (var row : rows)
            if (!window.contains(row.entry().window()))
                errors.add(new CatalogFileParser.Error(row.entry().rowNumber(), "interval", "OUTSIDE_COVERAGE", "时间行超出声明范围"));
        // 只有声明完整资源范围的占用快照才可以是空文件；空文件并不表示全库或全校空闲。
        if (ids.isEmpty() || ids.size() > 100 || (parsed.sourceRows() == 0 && !complete))
            throw new BusinessException(400, "VALIDATION_ERROR", "空占用文件只能用于有明确资源和时间范围的完整快照");
        for (long id : ids) {
            access.manage(actor, id);
            var resource = catalog.raw(id, false);
            if (!source.equals(resource.get("source_system")) || !mode.equals(resource.get("data_mode")))
                throw new BusinessException(400, "VALIDATION_ERROR", "覆盖资源必须来自指定来源及相同数据模式");
        }
        return new Prepared(parsed, rows, new Context(window, captured, complete ? "COMPLETE" : "PARTIAL", ids), errors);
    }

    /**
     * 保存并预览来源时间文件，展示涉及资源和逐行错误；数据库占用及覆盖在确认前不变化。
     */
    public CatalogIdempotency.Result preview(AuthActor actor, String key, MultipartFile file, String type, String source,
                                             String mode, String mappingText, String contextText) {
        access.administrator(actor);
        policies.mode(mode);
        if (!Set.of("EXTERNAL_OCCUPANCY", "CONFIRMED_FREE_INTERVALS").contains(type) || !source.matches("[A-Za-z0-9_-]{1,32}"))
            throw new BusinessException(400, "INVALID_IMPORT_TYPE", "占用阶段支持 EXTERNAL_OCCUPANCY 和 CONFIRMED_FREE_INTERVALS");
        var override = mapping(object(mappingText, 10000));
        var context = object(contextText, 12000);
        var saved = storage.save(file);
        try {
            var prepared = prepare(actor, storage.read(saved.key()), saved.key(), type, source, mode, override, context);
            int updated = 0;
            if ("EXTERNAL_OCCUPANCY".equals(type))
                for (var row : prepared.rows())
                    if (sql.queryForObject("SELECT COUNT(*) FROM external_occupancy WHERE resource_id=? AND source_system=? AND source_key=? AND data_mode=?", Long.class,
                            row.resource(), source, row.entry().sourceKey(), mode) > 0) updated++;
            var result = new LinkedHashMap<String, Object>();
            result.put("summary", Map.of("sourceRows", prepared.parsed().sourceRows(), "newRows", prepared.rows().size() - updated, "updateRows", updated, "errorRows", prepared.errors().size()));
            result.put("fieldMapping", prepared.parsed().mapping());
            result.put("errors", prepared.errors());
            result.put("completeness", prepared.context().completeness());
            result.put("snapshotResourceIds", prepared.context().resources().stream().map(Object::toString).toList());
            var payload = Map.of("fileHash", saved.hash(), "importType", type, "sourceSystem", source, "dataMode", mode, "mapping", override, "queryContext", context);
            var outcome = idempotency.execute(actor, "TEMPORAL_PREVIEW", key, payload, () -> {
                sql.update("INSERT INTO import_batch(source_system,import_type,data_mode,filename,file_storage_key,file_sha256,status,operator_id,field_mapping,preview_result,query_context) VALUES (?,?,?,?,?,?,'PREVIEWED',?,?,?,?)",
                        source, type, mode, saved.filename(), saved.key(), saved.hash(), Long.parseLong(actor.userId()),
                        json.writeValueAsString(override), json.writeValueAsString(result), json.writeValueAsString(context));
                long id = sql.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                for (var error : prepared.errors())
                    sql.update("INSERT INTO import_error(batch_id,`row_number`,field_name,error_code,message) VALUES (?,?,?,?,?)", id, error.rowNumber(), error.fieldName(), error.code(), error.message());
                catalog.audit(actor, "TEMPORAL_PREVIEW", "IMPORT_BATCH", id, null, "PREVIEWED");
                return new CatalogIdempotency.Result(201, view(batch(actor, id, false)), false);
            });
            if (outcome.replayed()) storage.delete(saved.key());
            return outcome;
        } catch (RuntimeException failure) {
            storage.delete(saved.key());
            throw failure;
        }
    }

    /**
     * 拒绝同资源、同来源的迟到快照；此处保守比较全部历史采集时间，而非仅比较重叠窗口。
     */
    private void fresh(long resource, String source, Instant captured) {
        // 同来源的新记录（包括已停用记录）不能被迟到的旧快照重新覆盖。
        long newer = sql.queryForObject("SELECT (SELECT COUNT(*) FROM availability_coverage WHERE resource_id=? AND source_system=? AND checked_at>?) + (SELECT COUNT(*) FROM external_occupancy WHERE resource_id=? AND source_system=? AND observed_at>?)", Long.class,
                resource, source, Timestamp.from(captured), resource, source, Timestamp.from(captured));
        if (newer > 0) throw new BusinessException(409, "SOURCE_SNAPSHOT_STALE", "已有较新的来源数据，请重新采集后导入");
    }

    /**
     * 持久化一段有来源、采集时刻、完整性与批次关联的覆盖证据，保留后续追溯依据。
     */
    private void coverage(long resource, String source, String mode, TimeWindow window, Instant captured, String completeness, long batch) {
        sql.update("INSERT INTO availability_coverage(resource_id,source_system,from_at,to_at,checked_at,completeness,import_batch_id,data_mode) VALUES (?,?,?,?,?,?,?,?)",
                resource, source, Timestamp.from(window.start()), Timestamp.from(window.end()), Timestamp.from(captured), completeness, batch, mode);
    }

    /**
     * 把与新确认窗口相交的旧证据停用，并保留窗口外的左右片段。
     * 片段沿用原采集时间及批次，不能借拆分延长证据有效期。
     */
    private void splitCoverage(long resource, String source, String mode, TimeWindow window) {
        var rows = sql.queryForList("SELECT * FROM availability_coverage WHERE resource_id=? AND source_system=? AND data_mode=? AND active=1 AND from_at<? AND to_at>? FOR UPDATE",
                resource, source, mode, Timestamp.from(window.end()), Timestamp.from(window.start()));
        for (var row : rows) {
            sql.update("UPDATE availability_coverage SET active=0 WHERE id=?", row.get("id"));
            Instant start = UtcTimes.instant(row.get("from_at")), end = UtcTimes.instant(row.get("to_at"));
            if (start.isBefore(window.start()))
                coverage(resource, source, mode, new TimeWindow(start, window.start()), UtcTimes.instant(row.get("checked_at")), row.get("completeness").toString(), ((Number) row.get("import_batch_id")).longValue());
            if (end.isAfter(window.end()))
                coverage(resource, source, mode, new TimeWindow(window.end(), end), UtcTimes.instant(row.get("checked_at")), row.get("completeness").toString(), ((Number) row.get("import_batch_id")).longValue());
        }
    }

    /**
     * 生成窗口外仍有效的占用片段；保留原来源、原因、批次和观察时间，使用保留前缀避免与源编号混淆。
     */
    private void fragment(Map<String, Object> row, TimeWindow window) {
        String key = "__fragment_" + row.get("id") + "_" + ImportStorage.hash(window.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        sql.update("INSERT INTO external_occupancy(resource_id,source_system,source_key,start_at,end_at,reason_code,import_batch_id,observed_at,data_mode) VALUES (?,?,?,?,?,?,?,?,?)",
                row.get("resource_id"), row.get("source_system"), key, Timestamp.from(window.start()), Timestamp.from(window.end()), row.get("reason_code"),
                row.get("import_batch_id"), Timestamp.from(UtcTimes.instant(row.get("observed_at"))), row.get("data_mode"));
    }

    /**
     * 仅替换相同资源、来源和模式在声明窗口内的占用，不影响其他来源的占用事实。
     */
    private void replaceOccupancy(long resource, String source, String mode, TimeWindow window) {
        var rows = sql.queryForList("SELECT * FROM external_occupancy WHERE resource_id=? AND source_system=? AND data_mode=? AND active=1 AND start_at<? AND end_at>? FOR UPDATE",
                resource, source, mode, Timestamp.from(window.end()), Timestamp.from(window.start()));
        for (var row : rows) {
            sql.update("UPDATE external_occupancy SET active=0,version=version+1 WHERE id=?", row.get("id"));
            Instant start = UtcTimes.instant(row.get("start_at")), end = UtcTimes.instant(row.get("end_at"));
            // 裁剪仅作用于确认范围；跨边界占用的左右部分仍有效，不能整条删掉。
            if (start.isBefore(window.start())) fragment(row, new TimeWindow(start, window.start()));
            if (end.isAfter(window.end())) fragment(row, new TimeWindow(window.end(), end));
        }
    }

    /**
     * 确认来源时间批次：重新解析、校验摘要与权限，再锁定所有资源并检查迟到证据。
     * 覆盖拆分、占用替换和批次提交在同一事务中进行，后续任一资源失败会回滚前序操作。
     */
    public CatalogIdempotency.Result commit(AuthActor actor, String key, long id) {
        var initial = batch(actor, id, false);
        String type = initial.get("import_type").toString(), source = initial.get("source_system").toString(), mode = initial.get("data_mode").toString();
        policies.mode(mode);
        if (!Set.of("EXTERNAL_OCCUPANCY", "CONFIRMED_FREE_INTERVALS").contains(type))
            throw new BusinessException(400, "INVALID_IMPORT_TYPE", "不是占用或空闲区间批次");
        return idempotency.execute(actor, "TEMPORAL_COMMIT:" + id, key, Map.of("batchId", Long.toString(id)), () -> {
            if ("COMMITTED".equals(initial.get("status")))
                return new CatalogIdempotency.Result(200, view(initial), false);
            byte[] bytes = storage.read(initial.get("file_storage_key").toString());
            if (!ImportStorage.hash(bytes).equals(initial.get("file_sha256")))
                throw new BusinessException(409, "IMPORT_FILE_CHANGED", "预览文件已变化，请重新上传");
            var override = mapping(object(initial.get("field_mapping").toString(), 10000));
            var prepared = prepare(actor, bytes, initial.get("file_storage_key").toString(), type, source, mode, override, object(initial.get("query_context").toString(), 12000));
            if (!prepared.errors().isEmpty())
                throw new BusinessException(422, "IMPORT_HAS_ERRORS", "时间导入存在阻断错误，请重新预览");
            var context = prepared.context();
            // 锁顺序固定：幂等 → 资源主键升序 → 批次；锁后重新检查当前范围。
            for (long resource : context.resources()) {
                catalog.raw(resource, true);
                access.manage(actor, resource);
                fresh(resource, source, context.captured());
            }
            var current = batch(actor, id, true);
            if ("COMMITTED".equals(current.get("status")))
                return new CatalogIdempotency.Result(200, view(current), false);
            for (long resource : context.resources()) {
                splitCoverage(resource, source, mode, context.window());
                if ("EXTERNAL_OCCUPANCY".equals(type)) {
                    if ("COMPLETE".equals(context.completeness()))
                        replaceOccupancy(resource, source, mode, context.window());
                    coverage(resource, source, mode, context.window(), context.captured(), context.completeness(), id);
                }
            }
            for (var bound : prepared.rows()) {
                var entry = bound.entry();
                // 空闲导入只为文件实际行建立证据；声明窗口中的未报告空白不补成空闲。
                if ("CONFIRMED_FREE_INTERVALS".equals(type)) {
                    if ("COMPLETE".equals(context.completeness()))
                        replaceOccupancy(bound.resource(), source, mode, entry.window());
                    coverage(bound.resource(), source, mode, entry.window(), context.captured(), context.completeness(), id);
                } else {
                    var existing = sql.queryForList("SELECT start_at,end_at FROM external_occupancy WHERE resource_id=? AND source_system=? AND source_key=? AND data_mode=?",
                            bound.resource(), source, entry.sourceKey(), mode);
                    if (!existing.isEmpty()) {
                        var original = new TimeWindow(UtcTimes.instant(existing.getFirst().get("start_at")), UtcTimes.instant(existing.getFirst().get("end_at")));
                        // 完整快照只能替换确认窗口；完全位于窗口外的旧记录也不能被编号复用移走。
                        if (!context.window().contains(original)
                                && ("PARTIAL".equals(context.completeness()) || !context.window().overlaps(original)))
                            throw new BusinessException(409, "IMPORT_RANGE_CONFLICT", "导入不能移动确认范围外的来源占用，请扩大核实范围");
                    }
                    sql.update("INSERT INTO external_occupancy(resource_id,source_system,source_key,start_at,end_at,reason_code,import_batch_id,observed_at,data_mode) VALUES (?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE start_at=VALUES(start_at),end_at=VALUES(end_at),reason_code=VALUES(reason_code),import_batch_id=VALUES(import_batch_id),observed_at=VALUES(observed_at),active=1,version=version+1",
                            bound.resource(), source, entry.sourceKey(), Timestamp.from(entry.window().start()), Timestamp.from(entry.window().end()), entry.reason(), id, Timestamp.from(context.captured()), mode);
                }
            }
            sql.update("UPDATE import_batch SET status='COMMITTED',committed_at=UTC_TIMESTAMP(3) WHERE id=?", id);
            catalog.audit(actor, "TEMPORAL_COMMIT", "IMPORT_BATCH", id, "PREVIEWED", "COMMITTED");
            return new CatalogIdempotency.Result(200, view(batch(actor, id, false)), false);
        });
    }
}
