package org.shen.ai.domain;

import jakarta.validation.constraints.*;

import java.util.Map;

/**
 * HTTP 输入仅包含用户文本和版本，不接收 ownerId、角色、工具 URL 或资源写入指令。
 */
public final class AiCommands {
    private AiCommands() {
    }

    public record Create(@NotBlank @Pattern(regexp = "REAL|DEMO") String dataMode) {
    }

    public record Send(@NotBlank @Size(max = 2000) String text,
                       @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") String clientMessageId,
                       @NotNull @Min(0) Integer expectedConditionVersion) {
    }

    public record Close(@NotNull @Min(0) Integer expectedConditionVersion) {
    }

    /**
     * evidence 必须引用本条用户文本的原片段；最终时间与人数由服务器从片段解析。
     */
    public record Extraction(Map<String, Object> changes, Map<String, String> evidence) {
    }
}
