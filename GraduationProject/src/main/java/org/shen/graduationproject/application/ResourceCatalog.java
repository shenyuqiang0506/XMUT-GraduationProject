package org.shen.graduationproject.application;

import lombok.RequiredArgsConstructor;

import java.util.*;

import jakarta.validation.Validator;
import org.shen.graduationproject.domain.CatalogCommands;
import org.shen.graduationproject.infrastructure.*;
import org.shen.platform.contracts.AuthActor;
import org.shen.platform.contracts.TraceIds;
import org.shen.platform.support.BusinessException;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 资源目录应用服务，负责场地、设备、审批组及管理范围的维护。
 * 来源可借用状态与平台开放状态分开保存；目录列表不计算具体时段可用性。
 * 写操作由幂等执行器提供事务，资源修改还需行锁和 expectedVersion 防止覆盖并发变更。
 */
@Service
@Profile("business")
@RequiredArgsConstructor
public class ResourceCatalog {
    private final BookingWriteCoordinator coordinator;
    private final JdbcTemplate sql;
    private final CatalogAccess access;
    private final CatalogIdempotency idempotency;
    private final AuthorizedUsers users;
    private final ObjectMapper json;
    private final Validator validator;

    /**
     * 校验资源字段及跨字段约束：设备需要资产信息，开放资源需要有效审批组，真实来源必须明确可借。
     */
    public void validate(CatalogCommands.Resource input) {
        if (!validator.validate(input).isEmpty())
            throw new BusinessException(400, "VALIDATION_ERROR", "资源字段不符合要求");
        if ("EQUIPMENT".equals(input.resourceType()) && (input.assetCode() == null || input.assetCode().isBlank() || input.equipmentType() == null || input.equipmentType().isBlank()))
            throw new BusinessException(400, "VALIDATION_ERROR", "设备需要资产编号和设备类型");
        if (input.approvalGroupId() != null && sql.queryForObject("SELECT COUNT(*) FROM approval_group WHERE id=? AND status='ACTIVE'", Long.class, CatalogAccess.id(input.approvalGroupId())) == 0)
            throw new BusinessException(422, "INVALID_APPROVAL_GROUP", "审批组不存在或已停用");
        if ("OPEN".equals(input.platformStatus()) && (input.approvalGroupId() == null || ("REAL".equals(input.dataMode()) && !"YES".equals(input.sourceBorrowable()))))
            throw new BusinessException(422, "RESOURCE_NOT_OPENABLE", "开放资源必须绑定审批组，真实来源必须明确可借用");
    }

    /**
     * 读取内部原始记录；lock=true 用于事务内协调资源修改。此方法本身不检查访问范围，调用方负责授权。
     */
    public Map<String, Object> raw(long id, boolean lock) {
        var rows = sql.queryForList("SELECT * FROM resource WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
        if (rows.isEmpty()) throw new BusinessException(404, "NOT_FOUND", "资源不存在或不可见");
        return rows.getFirst();
    }

    /**
     * 以来源系统、来源编号和数据模式构成目录身份，避免不同来源或测试资料互相覆盖。
     */
    public Long find(String source, String sourceId, String mode) {
        return sql.queryForList("SELECT id FROM resource WHERE source_system=? AND source_id=? AND data_mode=?", Long.class, source, sourceId, mode).stream().findFirst().orElse(null);
    }

    /**
     * 显式挑选对外字段，ID 转为字符串。没有查询时间窗口时，已开放资源仍返回 UNKNOWN。
     */
    public Map<String, Object> view(Map<String, Object> row) {
        var out = new LinkedHashMap<String, Object>();
        String[] columns = {"resource_type", "source_system", "source_id", "name", "campus", "building", "floor_no", "category", "subcategory", "capacity", "exam_capacity", "area", "source_borrowable", "platform_status", "data_mode", "remark", "version"};
        String[] names = {"resourceType", "sourceSystem", "sourceId", "name", "campus", "building", "floorNo", "category", "subcategory", "capacity", "examCapacity", "area", "sourceBorrowable", "platformStatus", "dataMode", "remark", "version"};
        for (int i = 0; i < columns.length; i++) out.put(names[i], row.get(columns[i]));
        out.put("id", row.get("id").toString());
        out.put("approvalGroupId", row.get("approval_group_id") == null ? null : row.get("approval_group_id").toString());
        out.put("availability", "OPEN".equals(row.get("platform_status")) ? "UNKNOWN" : "CLOSED");
        out.put("coverageStatus", "UNKNOWN");
        Map<String, String> features = new TreeMap<>();
        sql.queryForList("SELECT feature_code,feature_value FROM resource_feature WHERE resource_id=?", row.get("id")).forEach(f -> features.put(f.get("feature_code").toString(), f.get("feature_value").toString()));
        out.put("features", features);
        var equipment = sql.queryForList("SELECT asset_code,equipment_type,condition_status FROM equipment_detail WHERE resource_id=?", row.get("id"));
        out.put("assetCode", equipment.isEmpty() ? null : equipment.getFirst().get("asset_code"));
        out.put("equipmentType", equipment.isEmpty() ? null : equipment.getFirst().get("equipment_type"));
        out.put("conditionStatus", equipment.isEmpty() ? null : equipment.getFirst().get("condition_status"));
        return out;
    }

    public Map<String, Object> detail(AuthActor actor, long id) {
        var rows = sql.queryForList("SELECT r.* FROM resource r WHERE r.id=? AND (" + access.visibleSql(actor) + ")", id);
        if (rows.isEmpty()) throw new BusinessException(404, "NOT_FOUND", "资源不存在或不可见");
        var result = view(rows.getFirst());
        result.put("openingRules", sql.queryForList("SELECT weekday,start_local,end_local,valid_from,valid_to,enabled FROM resource_opening_rule WHERE resource_id=? ORDER BY weekday,start_local", id)
                .stream().map(r -> Map.of("weekday", r.get("weekday"), "startLocal", r.get("start_local").toString(),
                        "endLocal", r.get("end_local").toString(), "validFrom", r.get("valid_from").toString(),
                        "validTo", r.get("valid_to").toString(), "enabled", ((Number) r.get("enabled")).intValue() == 1)).toList());
        return result;
    }

    /**
     * 先应用当前用户可见范围，再拼接白名单筛选列；筛选值通过 JDBC 参数绑定传入。
     */
    public Map<String, Object> list(AuthActor actor, int page, int size, Map<String, String> filters) {
        if (page < 1 || page > 100000 || size < 1 || size > 100)
            throw new BusinessException(400, "VALIDATION_ERROR", "分页参数无效");
        StringBuilder where = new StringBuilder(" WHERE " + access.visibleSql(actor));
        List<Object> args = new ArrayList<>();
        Map<String, String> columns = Map.of("campus", "r.campus", "building", "r.building", "type", "r.resource_type", "dataMode", "r.data_mode", "category", "r.category");
        for (var entry : columns.entrySet()) {
            String value = filters.getOrDefault(entry.getKey(), "");
            if (value.isBlank()) continue;
            if (value.length() > 100 || (entry.getKey().equals("type") && !Set.of("VENUE", "EQUIPMENT").contains(value)) || (entry.getKey().equals("dataMode") && !Set.of("REAL", "DEMO").contains(value)))
                throw new BusinessException(400, "VALIDATION_ERROR", "筛选参数无效");
            where.append(" AND ").append(entry.getValue()).append("=?");
            args.add(value);
        }
        String capacity = filters.getOrDefault("capacityMin", "");
        if (!capacity.isBlank()) {
            try {
                int n = Integer.parseInt(capacity);
                if (n < 0) throw new NumberFormatException();
                where.append(" AND r.capacity>=?");
                args.add(n);
            } catch (NumberFormatException invalid) {
                throw new BusinessException(400, "VALIDATION_ERROR", "容量筛选必须为非负整数");
            }
        }
        long total = sql.queryForObject("SELECT COUNT(*) FROM resource r" + where, Long.class, args.toArray());
        args.add(size);
        args.add((page - 1) * size);
        var rows = sql.queryForList("SELECT r.* FROM resource r" + where + " ORDER BY r.id LIMIT ? OFFSET ?", args.toArray());
        return Map.of("items", rows.stream().map(this::view).toList(), "page", page, "size", size, "total", total);
    }

    public void audit(AuthActor actor, String action, String type, long id, String from, String to) {
        sql.update("INSERT INTO audit_log(actor_id,action,object_type,object_id,from_status,to_status,trace_id) VALUES (?,?,?,?,?,?,?)", Long.parseLong(actor.userId()), action, type, id, from, to, TraceIds.acceptOrCreate(MDC.get(TraceIds.MDC_KEY)));
    }

    /**
     * 整体维护设施属性及设备扩展字段，随外层资源写入事务共同提交。
     */
    private void extras(long id, CatalogCommands.Resource input) {
        sql.update("DELETE FROM resource_feature WHERE resource_id=?", id);
        if (input.features() != null)
            input.features().forEach((k, v) -> sql.update("INSERT INTO resource_feature(resource_id,feature_code,feature_value) VALUES (?,?,?)", id, k, v));
        if ("EQUIPMENT".equals(input.resourceType())) {
            if (sql.queryForObject("SELECT COUNT(*) FROM equipment_detail WHERE resource_id=?", Long.class, id) == 0) {
                sql.update("INSERT INTO equipment_detail(resource_id,asset_code,equipment_type,condition_status) VALUES (?,?,?,?)", id, input.assetCode(), input.equipmentType(), input.conditionStatus() == null ? "GOOD" : input.conditionStatus());
            } else {
                sql.update("UPDATE equipment_detail SET asset_code=?,equipment_type=?,condition_status=? WHERE resource_id=?", input.assetCode(), input.equipmentType(), input.conditionStatus() == null ? "GOOD" : input.conditionStatus(), id);
            }
        }
    }

    /**
     * 创建资源及扩展记录；必须在外层事务中调用，保持自增 ID 查询与插入使用同一连接。
     */
    public long insert(CatalogCommands.Resource r, Long batch, Map<String, Object> raw) {
        sql.update("INSERT INTO resource(resource_type,source_system,source_id,name,campus,building,floor_no,category,subcategory,capacity,exam_capacity,area,source_borrowable,platform_status,approval_group_id,data_mode,remark,raw_payload,import_batch_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                r.resourceType(), r.sourceSystem(), r.sourceId(), r.name(), r.campus(), r.building(), r.floorNo(), r.category(), r.subcategory(), r.capacity(), r.examCapacity(), r.area(), r.sourceBorrowable(), r.platformStatus(), r.approvalGroupId(), r.dataMode(), r.remark(), json.writeValueAsString(raw), batch);
        long id = sql.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        extras(id, r);
        return id;
    }

    /**
     * 更新允许变化的目录字段并递增版本；资源类型、来源标识与数据模式保持原身份。
     */
    public void update(long id, CatalogCommands.Resource r, Long batch, Map<String, Object> raw) {
        sql.update("UPDATE resource SET name=?,campus=?,building=?,floor_no=?,category=?,subcategory=?,capacity=?,exam_capacity=?,area=?,source_borrowable=?,platform_status=?,approval_group_id=?,remark=?,raw_payload=?,import_batch_id=?,version=version+1 WHERE id=?",
                r.name(), r.campus(), r.building(), r.floorNo(), r.category(), r.subcategory(), r.capacity(), r.examCapacity(), r.area(), r.sourceBorrowable(), r.platformStatus(), r.approvalGroupId(), r.remark(), json.writeValueAsString(raw), batch, id);
        extras(id, r);
    }

    /**
     * 预检权限和字段后进入幂等事务再次检查，避免直接依赖提交前的界面状态。
     */
    public CatalogIdempotency.Result create(AuthActor actor, String key, CatalogCommands.Resource input) {
        access.createInGroup(actor, input.approvalGroupId());
        validate(input);
        try {
            return idempotency.execute(actor, "CREATE_RESOURCE", key, input, () -> {
                validate(input);
                access.createInGroup(actor, input.approvalGroupId());
                long id = insert(input, null, Map.of());
                audit(actor, "CREATE_RESOURCE", "RESOURCE", id, null, input.platformStatus());
                return new CatalogIdempotency.Result(201, view(raw(id, false)), false);
            });
        } catch (DuplicateKeyException duplicate) {
            throw new BusinessException(409, "RESOURCE_EXISTS", "资源来源编号或资产编号已存在");
        }
    }

    /**
     * 只接受白名单字段，在资源行锁内比较版本，然后将局部字段合并为完整资源重新校验。
     */
    public CatalogIdempotency.Result patch(AuthActor actor, String key, long id, Map<String, Object> fields) {
        access.administrator(actor);
        access.manage(actor, id);
        Set<String> allowed = Set.of("name", "campus", "building", "floorNo", "category", "subcategory", "capacity", "examCapacity", "area", "sourceBorrowable", "platformStatus", "approvalGroupId", "remark", "features", "conditionStatus", "expectedVersion");
        if (!allowed.containsAll(fields.keySet()) || !(fields.get("expectedVersion") instanceof Number))
            throw new BusinessException(400, "VALIDATION_ERROR", "更新字段或版本无效");
        return idempotency.execute(actor, "UPDATE_RESOURCE:" + id, key, fields, () -> {
            coordinator.resources(List.of(id));
            var row = raw(id, true);
            access.manage(actor, id);
            Number expected = (Number) fields.get("expectedVersion");
            if (expected.longValue() != expected.doubleValue() || ((Number) row.get("version")).longValue() != expected.longValue())
                throw new BusinessException(409, "VERSION_CONFLICT", "资源已修改，请刷新后重试");
            var values = view(row);
            values.remove("id");
            values.remove("availability");
            values.remove("coverageStatus");
            values.remove("version");
            fields.forEach((k, v) -> {
                if (!k.equals("expectedVersion")) values.put(k, v);
            });
            CatalogCommands.Resource input;
            try {
                input = json.convertValue(values, CatalogCommands.Resource.class);
            } catch (RuntimeException invalid) {
                throw new BusinessException(400, "VALIDATION_ERROR", "资源字段格式无效");
            }
            // 更换审批组会改变管理边界，因此额外要求系统管理员权限。
            if (!Objects.equals(row.get("approval_group_id") == null ? null : row.get("approval_group_id").toString(), input.approvalGroupId()))
                access.systemAdministrator(actor);
            validate(input);
            Map<String, Object> payload = row.get("raw_payload") == null ? Map.of() : json.readValue(row.get("raw_payload").toString(), Map.class);
            update(id, input, row.get("import_batch_id") == null ? null : ((Number) row.get("import_batch_id")).longValue(), payload);
            audit(actor, "UPDATE_RESOURCE", "RESOURCE", id, row.get("platform_status").toString(), input.platformStatus());
            coordinator.changed(actor,List.of(id));
            return new CatalogIdempotency.Result(200, view(raw(id, false)), false);
        });
    }

    /**
     * 系统管理员查看全部审批组，资源管理员只查看自己属于的组；成员 ID 仍以字符串返回。
     */
    public Map<String, Object> groups(AuthActor actor) {
        access.administrator(actor);
        String scope = access.system(actor) ? "" : " WHERE EXISTS(SELECT 1 FROM approval_group_member m WHERE m.group_id=g.id AND m.user_id=" + Long.parseLong(actor.userId()) + ")";
        var rows = sql.queryForList("SELECT g.id,g.name,g.status FROM approval_group g" + scope + " ORDER BY g.id");
        rows.forEach(r -> {
            r.put("id", r.get("id").toString());
            r.put("memberUserIds", sql.queryForList("SELECT user_id FROM approval_group_member WHERE group_id=? ORDER BY user_id", Long.class, r.get("id")).stream().map(Object::toString).toList());
        });
        return Map.of("items", rows);
    }

    /**
     * 创建有效审批组并记录审计；数据库唯一约束将重复名称转换为业务冲突。
     */
    public CatalogIdempotency.Result createGroup(AuthActor actor, String key, CatalogCommands.Group input) {
        access.systemAdministrator(actor);
        try {
            return idempotency.execute(actor, "CREATE_GROUP", key, input, () -> {
                sql.update("INSERT INTO approval_group(name,status) VALUES (?,'ACTIVE')", input.name());
                long id = sql.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                audit(actor, "CREATE_GROUP", "APPROVAL_GROUP", id, null, "ACTIVE");
                return new CatalogIdempotency.Result(201, Map.of("id", Long.toString(id), "name", input.name(), "status", "ACTIVE"), false);
            });
        } catch (DuplicateKeyException duplicate) {
            throw new BusinessException(409, "GROUP_EXISTS", "审批组名称已存在");
        }
    }

    /**
     * 同一实现维护审批组成员或资源直属管理员。
     * 目标账号先通过身份服务检查；写事务锁定父记录后整体替换集合。表名由布尔分支固定选择，不来自请求字符串。
     */
    public CatalogIdempotency.Result members(AuthActor actor, String key, long id, CatalogCommands.Members input, boolean resource) {
        access.systemAdministrator(actor);
        input.userIds().forEach(users::requireManager);
        return idempotency.execute(actor, (resource ? "RESOURCE_MANAGERS:" : "GROUP_MEMBERS:") + id, key, new TreeSet<>(input.userIds()), () -> {
            String parent = resource ? "resource" : "approval_group";
            if (sql.queryForList("SELECT id FROM " + parent + " WHERE id=? FOR UPDATE", Long.class, id).isEmpty())
                throw new BusinessException(404, "NOT_FOUND", "资源或审批组不存在");
            String table = resource ? "resource_manager" : "approval_group_member", column = resource ? "resource_id" : "group_id";
            sql.update("DELETE FROM " + table + " WHERE " + column + "=?", id);
            for (String uid : input.userIds())
                sql.update("INSERT INTO " + table + "(" + column + ",user_id) VALUES (?,?)", id, Long.parseLong(uid));
            audit(actor, resource ? "RESOURCE_MANAGERS" : "GROUP_MEMBERS", resource ? "RESOURCE" : "APPROVAL_GROUP", id, null, null);
            return new CatalogIdempotency.Result(200, Map.of("id", Long.toString(id), "userIds", new TreeSet<>(input.userIds())), false);
        });
    }
}
