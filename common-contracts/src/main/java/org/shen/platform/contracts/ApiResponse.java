package org.shen.platform.contracts;

/**
 * 统一 HTTP 响应契约。
 * code 表示业务结果，message 用于提示，data 承载成功数据，traceId 用于跨服务定位请求。
 * 错误响应不附带业务数据；HTTP 状态码由控制器或异常处理器设置。
 */
public record ApiResponse<T>(String code, String message, T data, String traceId) {
    public static <T> ApiResponse<T> success(T data, String traceId) {
        return new ApiResponse<>("OK", "成功", data, traceId);
    }

    public static ApiResponse<Void> error(String code, String message, String traceId) {
        return new ApiResponse<>(code, message, null, traceId);
    }
}
