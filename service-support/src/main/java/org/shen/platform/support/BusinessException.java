package org.shen.platform.support;

/**
 * 可预期的业务拒绝，携带 HTTP 状态码、稳定错误码与用户提示。
 * 由统一异常处理器输出，调用方可依赖 code 判断冲突、权限或参数问题。
 */
public class BusinessException extends RuntimeException {
    private final int status;
    private final String code;

    public BusinessException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
