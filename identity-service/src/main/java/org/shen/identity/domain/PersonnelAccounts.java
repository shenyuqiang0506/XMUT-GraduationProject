package org.shen.identity.domain;

import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.shen.platform.support.BusinessException;

/**
 * 学生、教师批量开户的纯输入校验，不访问数据库、不记录密码。
 * Hutool 只负责字符串处理和格式判断；编号保持原有大小写及前导零。
 */
public final class PersonnelAccounts {
    private PersonnelAccounts() { }

    public record Error(int row, String field, String code, String message) { }

    // errors 用于页面展示；users 含密码，仅在服务端本次请求内使用，禁止直接作为响应返回。
    public record Prepared(List<AuthCommands.CreateUser> users, List<Error> errors) { }

    public static Prepared prepare(AuthCommands.PersonnelImport input) {
        if (input == null || input.rows() == null || input.rows().isEmpty() || input.rows().size() > 50)
            throw new BusinessException(400, "VALIDATION_ERROR", "每批需要 1–50 条人员记录");
        var users = new ArrayList<AuthCommands.CreateUser>();
        var errors = new ArrayList<Error>();
        var numbers = new HashSet<String>();
        for (int index = 0; index < input.rows().size(); index++) {
            int row = index + 1;
            var value = input.rows().get(index);
            if (value == null) {
                errors.add(new Error(row, "row", "REQUIRED", "人员记录不能为空"));
                continue;
            }
            // 只移除编号和姓名两端空白；密码必须原样处理，不能 trim 后悄悄改变登录凭据。
            String number = StrUtil.trim(value.number()), name = StrUtil.trim(value.displayName());
            int before = errors.size();
            if (!ReUtil.isMatch("[A-Za-z0-9_.-]{3,64}", number))
                errors.add(new Error(row, "number", "INVALID_NUMBER", "学号或工号需为 3–64 位字母、数字、下划线、点或短横线"));
            else if (!numbers.add(number))
                errors.add(new Error(row, "number", "DUPLICATE_NUMBER", "本批学号或工号重复"));
            if (StrUtil.isBlank(name) || name.length() > 100)
                errors.add(new Error(row, "displayName", "INVALID_NAME", "姓名需为 1–100 个字符"));
            if (!"STUDENT".equals(value.roleCode()) && !"TEACHER".equals(value.roleCode()))
                errors.add(new Error(row, "roleCode", "INVALID_ROLE", "批量导入仅允许学生或教师，不授予管理权限"));
            String password = value.initialPassword();
            if (StrUtil.isBlank(password) || password.length() < 12 || password.length() > 72
                    || password.getBytes(StandardCharsets.UTF_8).length > 72)
                errors.add(new Error(row, "initialPassword", "INVALID_PASSWORD", "初始密码需为 12–72 个字符，且不超过 72 个 UTF-8 字节"));
            if (before == errors.size())
                users.add(new AuthCommands.CreateUser(number, password, name, Set.of(value.roleCode())));
        }
        return new Prepared(List.copyOf(users), List.copyOf(errors));
    }
}
