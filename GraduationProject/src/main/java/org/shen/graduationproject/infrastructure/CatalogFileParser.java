package org.shen.graduationproject.infrastructure;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.*;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.shen.graduationproject.domain.CatalogCommands;
import org.shen.platform.support.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Excel/JSON 资源目录解析器，只负责格式转换和逐行错误定位。
 * 统计以文件实际数据行为准，不采信接口导出中的 totalResult 等分页总数。
 * 显式映射优先于已知别名；未知容量保留 null，资源来源编号不主动转为数字。
 */
@Component
@Profile("business")
public class CatalogFileParser {
    public record Candidate(int rowNumber, CatalogCommands.Resource resource, Map<String, Object> raw) {
    }

    public record Error(int rowNumber, String fieldName, String code, String message) {
    }

    public record Parsed(int sourceRows, List<Candidate> rows, List<Error> errors, Map<String, String> mapping) {
    }

    public record Source(int row, Map<String, Object> values) {
    }

    private final ObjectMapper json;
    private static final Map<String, List<String>> ALIASES = new LinkedHashMap<>();

    static {
        ALIASES.put("sourceId", List.of("场地编号", "cdbh", "cd_id", "sourceId"));
        ALIASES.put("name", List.of("场地名称", "cdmc", "name"));
        ALIASES.put("campus", List.of("校区", "xqmc", "campus"));
        ALIASES.put("building", List.of("楼号", "jxlmc", "building"));
        ALIASES.put("floorNo", List.of("楼层号", "lch", "floorNo"));
        ALIASES.put("category", List.of("场地类别", "cdlbmc", "category"));
        ALIASES.put("subcategory", List.of("二级类别", "cdejlbmc", "subcategory"));
        ALIASES.put("capacity", List.of("座位数", "zws", "capacity"));
        ALIASES.put("examCapacity", List.of("考试座位数", "kszws1", "examCapacity"));
        ALIASES.put("area", List.of("建筑面积", "jzmj", "area"));
        ALIASES.put("sourceBorrowable", List.of("是否可借用", "sfkjy", "sourceBorrowable"));
        ALIASES.put("remark", List.of("场地备注", "bz", "remark"));
        ALIASES.put("resourceType", List.of("resourceType"));
        ALIASES.put("assetCode", List.of("assetCode"));
        ALIASES.put("equipmentType", List.of("equipmentType"));
    }

    private static final class FieldError extends RuntimeException {
        final String field, code;

        FieldError(String field, String code, String message) {
            super(message);
            this.field = field;
            this.code = code;
        }
    }

    public CatalogFileParser(ObjectMapper json) {
        this.json = json;
        ZipSecureFile.setMaxEntrySize(16777216);
        ZipSecureFile.setMaxTextSize(8388608);
    }

    /**
     * 复用严格的 Excel/JSON 读取；完整占用快照可显式提交空数组。
     */
    public List<Source> readRows(byte[] bytes, String key, boolean allowEmpty) {
        try {
            var out = new ArrayList<Source>();
            if (key.endsWith(".json")) {
                Object decoded = json.readValue(bytes, Object.class);
                if (decoded instanceof Map<?, ?> root) {
                    Object data = root.containsKey("data") ? root.get("data") : root;
                    if (data instanceof Map<?, ?> map)
                        data = map.containsKey("items") ? map.get("items") : map.containsKey("rows") ? map.get("rows") : map.get("list");
                    decoded = data;
                }
                if (!(decoded instanceof List<?> list) || list.size() > 2000)
                    throw new BusinessException(400, "INVALID_IMPORT_FILE", "JSON 需要数组或 items/rows/list 数组，最多 2000 条");
                for (int i = 0; i < list.size(); i++) {
                    Object item = list.get(i);
                    if (!(item instanceof Map<?, ?> map)) {
                        out.add(new Source(i + 1, Map.of()));
                        continue;
                    }
                    var values = new LinkedHashMap<String, Object>();
                    map.forEach((k, v) -> values.put(k.toString(), v));
                    out.add(new Source(i + 1, values));
                }
            } else {
                try (Workbook book = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
                    if (book.getNumberOfSheets() == 0)
                        throw new BusinessException(400, "INVALID_IMPORT_FILE", "Excel 缺少工作表");
                    var sheet = book.getSheetAt(0);
                    var header = sheet.getRow(0);
                    if (header == null || header.getLastCellNum() > 100)
                        throw new BusinessException(400, "INVALID_IMPORT_FILE", "Excel 表头无效");
                    // 按 Excel 的显示格式读取值，可保留文本编号及带零格式；不对公式求值，导入只接收原始数据。
                    DataFormatter format = new DataFormatter(Locale.ROOT);
                    List<String> columns = new ArrayList<>();
                    Set<String> used = new HashSet<>();
                    for (int col = 0; col < header.getLastCellNum(); col++) {
                        String name = format.formatCellValue(header.getCell(col)).strip();
                        if (!name.isEmpty() && !used.add(name))
                            throw new BusinessException(400, "INVALID_IMPORT_FILE", "Excel 表头不能重复");
                        columns.add(name);
                    }
                    if (sheet.getLastRowNum() > 2000)
                        throw new BusinessException(400, "INVALID_IMPORT_FILE", "Excel 最多 2000 条");
                    for (int line = 1; line <= sheet.getLastRowNum(); line++) {
                        Row row = sheet.getRow(line);
                        if (row == null) continue;
                        Map<String, Object> values = new LinkedHashMap<>();
                        boolean nonempty = false;
                        for (int col = 0; col < columns.size(); col++) {
                            var cell = row.getCell(col);
                            if (cell != null && cell.getCellType() == CellType.FORMULA)
                                throw new BusinessException(400, "INVALID_IMPORT_FILE", "导入文件需要原始值，不能包含公式");
                            String value = format.formatCellValue(cell).strip();
                            nonempty |= !value.isEmpty();
                            if (!columns.get(col).isEmpty()) values.put(columns.get(col), value);
                        }
                        if (nonempty) out.add(new Source(line + 1, values));
                    }
                }
            }
            if (out.isEmpty() && !allowEmpty) throw new BusinessException(400, "INVALID_IMPORT_FILE", "文件没有数据行");
            return out;
        } catch (BusinessException controlled) {
            throw controlled;
        } catch (Exception invalid) {
            throw new BusinessException(400, "INVALID_IMPORT_FILE", "无法读取 Excel/JSON 文件");
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value).strip();
    }

    private static String optional(Object value) {
        String s = text(value);
        return s == null || s.isEmpty() ? null : s;
    }

    /**
     * 用精确整数转换拒绝小数和溢出；空值保留未知，非负约束由后续资源校验执行。
     */
    private Integer integer(Object value, String field) {
        String s = optional(value);
        if (s == null) return null;
        try {
            return new BigDecimal(s).intValueExact();
        } catch (RuntimeException invalid) {
            throw new FieldError(field, "INVALID_NUMBER", "需要非负整数，空值表示未知");
        }
    }

    private BigDecimal decimal(Object value) {
        String s = optional(value);
        if (s == null) return null;
        try {
            return new BigDecimal(s);
        } catch (RuntimeException invalid) {
            throw new FieldError("area", "INVALID_NUMBER", "面积需要有效数字");
        }
    }

    /**
     * 建立字段映射后逐行标准化；每行保留来源行号，允许预览一次列出多行错误。
     */
    public Parsed parse(byte[] bytes, String key, String source, String mode, String group, Map<String, String> override) {
        List<Source> sources = readRows(bytes, key, false);
        Set<String> columns = new HashSet<>();
        sources.forEach(r -> columns.addAll(r.values().keySet()));
        if (!ALIASES.keySet().containsAll(override.keySet()) || override.values().stream().anyMatch(v -> v == null || v.length() > 100 || v.toLowerCase(Locale.ROOT).matches(".*(password|token|cookie|authorization|密码|口令).*")))
            throw new BusinessException(400, "INVALID_FIELD_MAPPING", "字段映射包含无效或敏感字段");
        Map<String, String> mapping = new LinkedHashMap<>();
        ALIASES.forEach((field, aliases) -> {
            String selected = override.get(field);
            if (selected == null) selected = aliases.stream().filter(columns::contains).findFirst().orElse(null);
            if (selected != null) mapping.put(field, selected);
        });
        var rows = new ArrayList<Candidate>();
        var errors = new ArrayList<Error>();
        Set<String> ids = new HashSet<>();
        for (Source row : sources) {
            try {
                Map<String, Object> values = new HashMap<>();
                mapping.forEach((field, column) -> values.put(field, row.values().get(column)));
                String id = optional(values.get("sourceId"));
                if (id == null) throw new FieldError("sourceId", "MISSING_SOURCE_ID", "来源编号不能为空");
                if (!override.containsKey("sourceId")) {
                    var declared = ALIASES.get("sourceId").stream().map(k -> optional(row.values().get(k))).filter(Objects::nonNull).distinct().toList();
                    if (declared.size() > 1)
                        throw new FieldError("sourceId", "SOURCE_ID_CONFLICT", "多个来源编号不一致，请明确选择字段映射");
                }
                if (!ids.add(id)) throw new FieldError("sourceId", "DUPLICATE_SOURCE_ID", "同一文件中来源编号重复");
                String borrow = optional(values.get("sourceBorrowable"));
                borrow = borrow == null ? "UNKNOWN" : borrow.toUpperCase(Locale.ROOT);
                borrow = switch (borrow) {
                    case "是" -> "YES";
                    case "否" -> "NO";
                    case "未知" -> "UNKNOWN";
                    default -> borrow;
                };
                if (!Set.of("YES", "NO", "UNKNOWN").contains(borrow))
                    throw new FieldError("sourceBorrowable", "INVALID_BORROWABLE", "可借用标记需要是/否/未知");
                var input = new CatalogCommands.Resource(optional(values.get("resourceType")) == null ? "VENUE" : text(values.get("resourceType")), source, id, optional(values.get("name")),
                        optional(values.get("campus")), optional(values.get("building")), optional(values.get("floorNo")), optional(values.get("category")), optional(values.get("subcategory")),
                        integer(values.get("capacity"), "capacity"), integer(values.get("examCapacity"), "examCapacity"), decimal(values.get("area")), borrow, "DRAFT", group, mode, optional(values.get("remark")), Map.of(), optional(values.get("assetCode")), optional(values.get("equipmentType")), "GOOD");
                // 原始行只保留白名单字段及简单值，避免将 cookie、token 等无关敏感列写入资源扩展数据。
                var raw = new LinkedHashMap<String, Object>();
                Set<String> allowed = new HashSet<>(mapping.values());
                ALIASES.values().forEach(allowed::addAll);
                allowed.addAll(List.of("sydxmc", "jgmc", "使用部门", "场地管理员", "托管部门"));
                row.values().forEach((k, v) -> {
                    if (allowed.contains(k) && (v == null || v instanceof String || v instanceof Number || v instanceof Boolean))
                        raw.put(k, v);
                });
                rows.add(new Candidate(row.row(), input, raw));
            } catch (FieldError invalid) {
                errors.add(new Error(row.row(), invalid.field, invalid.code, invalid.getMessage()));
            }
        }
        return new Parsed(sources.size(), rows, errors, mapping);
    }
}
