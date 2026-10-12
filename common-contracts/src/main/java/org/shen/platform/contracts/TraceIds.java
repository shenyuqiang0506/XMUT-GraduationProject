package org.shen.platform.contracts;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 请求追踪编号的公共规则，供网关、Servlet 服务及内部调用共同使用。
 * 只接收长度和字符集符合约束的外部编号，防止任意文本进入日志上下文。
 */
public final class TraceIds {
    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private TraceIds() {
    }

    /**
     * 保留合法的已有编号，使跨服务日志可关联；缺失或非法编号统一重新生成。
     */
    public static String acceptOrCreate(String value) {
        return value != null && VALID.matcher(value).matches()
                ? value : UUID.randomUUID().toString();
    }
}
