package org.shen.ai.domain;

/**
 * 可恢复的模型/工具失败，仅携带稳定原因；响应和日志不暴露模型原文或底层异常内容。
 */
public final class AiFailure extends RuntimeException {
    private final String code;

    public AiFailure(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
