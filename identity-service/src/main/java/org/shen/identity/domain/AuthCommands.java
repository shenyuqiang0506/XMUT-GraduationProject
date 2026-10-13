package org.shen.identity.domain;

import java.util.Set;
import java.util.List;

import jakarta.validation.constraints.*;

/**
 * 身份接口输入契约，通过 Jakarta Validation 限制格式、长度与枚举范围。
 * expectedVersion 表示调用方读取的身份版本，用于拒绝覆盖他人的并发修改。
 * 密码还需在应用层校验 UTF-8 字节长度，以满足 BCrypt 的 72 字节限制。
 */
public final class AuthCommands {
    private AuthCommands() {
    }

    public record Login(@NotBlank @Size(max = 64) String username,
                        @NotBlank @Size(max = 72) String password,
                        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") String deviceId,
                        @NotBlank @Pattern(regexp = "WEB|IOS|ANDROID") String deviceType) {
    }

    public record Refresh(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String refreshToken,
                          @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") String deviceId) {
    }

    // 学生的 username 填学号，教师填工号；统一作为字符串账号，不转换数字或添加角色前缀。
    public record CreateUser(@NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{3,64}") String username,
                             @NotBlank @Size(min = 12, max = 72) String initialPassword,
                             @NotBlank @Size(max = 100) String displayName,
                             @NotEmpty @Size(max = 4) Set<@Pattern(regexp = "STUDENT|TEACHER|RESOURCE_ADMIN|SYSTEM_ADMIN") String> roleCodes) {
    }

    public record Status(@NotBlank @Pattern(regexp = "ACTIVE|DISABLED") String status,
                         @NotNull @Min(0) Long expectedVersion) {
    }

    public record Roles(
            @NotEmpty @Size(max = 4) Set<@Pattern(regexp = "STUDENT|TEACHER|RESOURCE_ADMIN|SYSTEM_ADMIN") String> roleCodes,
            @NotNull @Min(0) Long expectedVersion) {
    }

    /**
     * 批量开户的人员行。number 为学号或工号，roleCode 仅接受 STUDENT、TEACHER。
     * 不提供管理员角色字段，审批授权必须另走账号角色和审批组管理流程。
     * 初始密码由调用方逐人提供，不使用学号、工号作为默认密码，也不在响应中返回。
     */
    public record PersonnelRow(String number, String displayName, String roleCode, String initialPassword) {
    }

    /**
     * 文件格式无关的导入边界，未来 Excel/CSV 解析器可将数据映射到此结构。
     * 每批最多 50 人，限制 BCrypt 计算和事务规模；逐行错误由预览统一返回。
     */
    public record PersonnelImport(@NotEmpty @Size(max = 50) List<PersonnelRow> rows) {
    }
}
