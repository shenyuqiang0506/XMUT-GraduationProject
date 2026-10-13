package org.shen.graduationproject.application;

import lombok.RequiredArgsConstructor;

import java.util.*;

import org.shen.graduationproject.domain.CatalogCommands;
import org.shen.graduationproject.infrastructure.*;
import org.shen.platform.contracts.AuthActor;
import org.shen.platform.support.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

/**
 * 资源目录的两阶段导入：先预览并保存原文件，再显式确认写库。
 * 预览结果只用于展示；确认时重新读取文件、核验摘要并检查当前管理权限。
 * 批次内任一资源失败都会回滚整批数据库变更，不删除文件之外的既有目录资源。
 */
@Service
@Profile("business")
@RequiredArgsConstructor
public class CatalogImports {
    private final BookingWriteCoordinator coordinator;
    private final JdbcTemplate sql;
    private final CatalogAccess access;
    private final CatalogIdempotency idempotency;
    private final ImportStorage storage;
    private final CatalogFileParser parser;
    private final ResourceCatalog catalog;
    private final ObjectMapper json;
    private final TemporalImports temporal;

    private Map<String, Object> object(String text, int maximum) {
        try {
            if (text.length() > maximum) throw new IllegalArgumentException();
            Object value = json.readValue(text, Object.class);
            if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException();
            return (Map<String, Object>) value;
        } catch (RuntimeException invalid) {
            throw new BusinessException(400, "VALIDATION_ERROR", "映射或查询条件需要有效 JSON 对象");
        }
    }

    private String group(Map<String, Object> context) {
        Object group = context.get("approvalGroupId");
        if (group == null) return null;
        if (!(group instanceof String id) || !id.matches("[1-9][0-9]{0,18}"))
            throw new BusinessException(400, "VALIDATION_ERROR", "审批组编号需要字符串 ID");
        try {
            Long.parseLong(id);
            return id;
        } catch (NumberFormatException invalid) {
            throw new BusinessException(400, "VALIDATION_ERROR", "审批组编号无效");
        }
    }

    /**
     * 系统管理员可查看所有批次；其他管理员只可读取自己的批次，隐藏他人批次是否存在。
     */
    private Map<String, Object> batch(AuthActor actor, long id, boolean lock) {
        access.administrator(actor);
        var rows = sql.queryForList("SELECT * FROM import_batch WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
        if (rows.isEmpty() || (!access.system(actor) && !actor.userId().equals(rows.getFirst().get("operator_id").toString())))
            throw new BusinessException(404, "NOT_FOUND", "导入批次不存在或不可见");
        return rows.getFirst();
    }

    /**
     * 只暴露批次摘要、映射和错误，不向客户端返回服务器文件存储路径。
     */
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

    public Map<String, Object> get(AuthActor actor, long id) {
        return view(batch(actor, id, false));
    }

    /**
     * 新来源资源检查所属审批组创建权限，已有资源检查当前管理范围；确认时也会重新执行。
     */
    private void permitted(AuthActor actor, CatalogCommands.Resource resource) {
        Long existing = catalog.find(resource.sourceSystem(), resource.sourceId(), resource.dataMode());
        if (existing == null) access.createInGroup(actor, resource.approvalGroupId());
        else access.manage(actor, existing);
    }

    /**
     * 保存上传文件并解析每行，汇总新增、更新和阻断错误，但此时不写入资源目录。
     * 目录以外的导入类型分派给时间数据导入服务。
     */
    public CatalogIdempotency.Result preview(AuthActor actor, String key, MultipartFile file, String type, String source, String mode, String mappingText, String contextText) {
        if (!"RESOURCE_CATALOG".equals(type))
            return temporal.preview(actor, key, file, type, source, mode, mappingText, contextText);
        access.administrator(actor);
        if (!"RESOURCE_CATALOG".equals(type) || !source.matches("[A-Za-z0-9_-]{1,32}") || !Set.of("REAL", "DEMO").contains(mode))
            throw new BusinessException(400, "INVALID_IMPORT_TYPE", "当前阶段支持 RESOURCE_CATALOG，必须指定来源和 REAL/DEMO");
        var map = object(mappingText, 10000);
        Map<String, String> override = new TreeMap<>();
        map.forEach((k, v) -> {
            if (!(v instanceof String))
                throw new BusinessException(400, "INVALID_FIELD_MAPPING", "映射值需要列名字符串");
            override.put(k, (String) v);
        });
        var context = object(contextText, 4000);
        String group = group(context);
        if (!Set.of("approvalGroupId", "capturedAt", "from", "to", "completeness", "sourceDescription", "queryDate", "periods").containsAll(context.keySet()))
            throw new BusinessException(400, "VALIDATION_ERROR", "查询条件包含未知字段");
        var saved = storage.save(file);
        try {
            var parsed = parser.parse(storage.read(saved.key()), saved.key(), source, mode, group, override);
            var errors = new ArrayList<>(parsed.errors());
            int inserted = 0, updated = 0;
            for (var row : parsed.rows()) {
                try {
                    catalog.validate(row.resource());
                    permitted(actor, row.resource());
                    if (catalog.find(source, row.resource().sourceId(), mode) == null) inserted++;
                    else updated++;
                } catch (BusinessException invalid) {
                    errors.add(new CatalogFileParser.Error(row.rowNumber(), "resource", invalid.code(), invalid.getMessage()));
                }
            }
            Map<String, Object> summary = Map.of("sourceRows", parsed.sourceRows(), "newRows", inserted, "updateRows", updated, "errorRows", errors.size());
            Map<String, Object> result = Map.of("summary", summary, "fieldMapping", parsed.mapping(), "errors", errors, "requiresConfirmation", true);
            var payload = Map.of("fileHash", saved.hash(), "sourceSystem", source, "dataMode", mode, "importType", type, "mapping", override, "queryContext", context);
            var outcome = idempotency.execute(actor, "IMPORT_PREVIEW", key, payload, () -> {
                sql.update("INSERT INTO import_batch(source_system,import_type,data_mode,filename,file_storage_key,file_sha256,status,operator_id,field_mapping,preview_result,query_context) VALUES (?,?,?,?,?,?,'PREVIEWED',?,?,?,?)", source, type, mode, saved.filename(), saved.key(), saved.hash(), Long.parseLong(actor.userId()), json.writeValueAsString(parsed.mapping()), json.writeValueAsString(result), json.writeValueAsString(context));
                long id = sql.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                for (var error : errors)
                    sql.update("INSERT INTO import_error(batch_id,`row_number`,field_name,error_code,message) VALUES (?,?,?,?,?)", id, error.rowNumber(), error.fieldName(), error.code(), error.message());
                catalog.audit(actor, "IMPORT_PREVIEW", "IMPORT_BATCH", id, null, "PREVIEWED");
                return new CatalogIdempotency.Result(201, view(batch(actor, id, false)), false);
            });
            // 幂等重试复用原批次，删除本次重复上传产生的新文件，避免无归属文件累积。
            if (outcome.replayed()) storage.delete(saved.key());
            return outcome;
        } catch (RuntimeException failure) {
            storage.delete(saved.key());
            throw failure;
        }
    }

    /**
     * 锁定批次并核验文件摘要、已保存映射和当前权限，按来源身份新增或更新资源。
     * 批次标记、资源及审计共享幂等事务；错误或唯一约束冲突会整体回滚。
     */
    public CatalogIdempotency.Result commit(AuthActor actor, String key, long id) {
        var initialBatch = batch(actor, id, false);
        if (!"RESOURCE_CATALOG".equals(initialBatch.get("import_type")))
            return temporal.commit(actor, key, id);
        try {
            return idempotency.execute(actor, "IMPORT_COMMIT:" + id, key, Map.of("batchId", Long.toString(id)), () -> {
                var batch = batch(actor, id, true);
                if ("COMMITTED".equals(batch.get("status")))
                    return new CatalogIdempotency.Result(200, view(batch), false);
                Map<String, Object> preview = json.readValue(batch.get("preview_result").toString(), Map.class);
                if (((Number) ((Map<?, ?>) preview.get("summary")).get("errorRows")).intValue() > 0)
                    throw new BusinessException(422, "IMPORT_HAS_ERRORS", "导入存在阻断错误，请修正文件后重新预览");
                byte[] bytes = storage.read(batch.get("file_storage_key").toString());
                if (!ImportStorage.hash(bytes).equals(batch.get("file_sha256")))
                    throw new BusinessException(409, "IMPORT_FILE_CHANGED", "预览文件已变化，请重新上传");
                var mapping = (Map<String, String>) json.readValue(batch.get("field_mapping").toString(), Map.class);
                var context = (Map<String, Object>) json.readValue(batch.get("query_context").toString(), Map.class);
                var parsed = parser.parse(bytes, batch.get("file_storage_key").toString(), batch.get("source_system").toString(), batch.get("data_mode").toString(), group(context), mapping);
                if (!parsed.errors().isEmpty())
                    throw new BusinessException(422, "IMPORT_HAS_ERRORS", "文件无法按已保存映射确认");
                // 对已有资源按主键升序加锁，与其他资源写入保持一致，降低多资源交叉操作的死锁风险。
                var ids = parsed.rows().stream().map(r -> catalog.find(r.resource().sourceSystem(), r.resource().sourceId(), r.resource().dataMode())).filter(Objects::nonNull).distinct().sorted().toList();
                coordinator.resources(ids);
                for (long resource : ids) catalog.raw(resource, true);
                int inserted = 0, updated = 0;
                for (var row : parsed.rows().stream().sorted(Comparator.comparing(r -> r.resource().sourceId())).toList()) {
                    var input = row.resource();
                    permitted(actor, input);
                    Long existing = catalog.find(input.sourceSystem(), input.sourceId(), input.dataMode());
                    if (existing == null) {
                        catalog.validate(input);
                        long resource = catalog.insert(input, id, row.raw());
                        catalog.audit(actor, "IMPORT_CREATE", "RESOURCE", resource, null, "DRAFT");
                        inserted++;
                    } else {
                        var current = catalog.raw(existing, true);
                        if (!input.resourceType().equals(current.get("resource_type")))
                            throw new BusinessException(422, "RESOURCE_TYPE_CHANGED", "已有来源资源不能改变场地/设备类型");
                        var fields = (Map<String, Object>) json.convertValue(input, Map.class);
                        fields.put("approvalGroupId", current.get("approval_group_id") == null ? null : current.get("approval_group_id").toString());
                        String status = current.get("platform_status").toString();
                        if ("OPEN".equals(status) && "REAL".equals(input.dataMode()) && !"YES".equals(input.sourceBorrowable()))
                            status = "CLOSED";
                        fields.put("platformStatus", status);
                        // 来源导入不能覆盖人工维护的设施、设备状态和审批组；已开放真实资源失去可借证明时会关闭。
                        var currentView = catalog.view(current);
                        fields.put("features", currentView.get("features"));
                        fields.put("conditionStatus", currentView.get("conditionStatus"));
                        var amended = json.convertValue(fields, CatalogCommands.Resource.class);
                        catalog.validate(amended);
                        catalog.update(existing, amended, id, row.raw());
                        catalog.audit(actor, "IMPORT_UPDATE", "RESOURCE", existing, current.get("platform_status").toString(), status);
                        updated++;
                    }
                }
                var summary = new LinkedHashMap<String, Object>(preview);
                summary.put("summary", Map.of("sourceRows", parsed.sourceRows(), "newRows", inserted, "updateRows", updated, "errorRows", 0));
                summary.put("requiresConfirmation", false);
                summary.put("affectedApplications",coordinator.changed(actor,ids));
                sql.update("UPDATE import_batch SET status='COMMITTED',committed_at=UTC_TIMESTAMP(3),preview_result=? WHERE id=?", json.writeValueAsString(summary), id);
                catalog.audit(actor, "IMPORT_COMMIT", "IMPORT_BATCH", id, "PREVIEWED", "COMMITTED");
                return new CatalogIdempotency.Result(200, view(batch(actor, id, false)), false);
            });
        } catch (DuplicateKeyException duplicate) {
            throw new BusinessException(409, "RESOURCE_EXISTS", "导入资源的来源或资产编号冲突，请重新预览");
        }
    }
}
