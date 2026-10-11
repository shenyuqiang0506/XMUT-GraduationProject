package org.shen.platform.contracts;

import java.util.UUID;
import java.util.regex.Pattern;

public final class TraceIds {
    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    private TraceIds() {}
    public static String acceptOrCreate(String value) {
        return value != null && VALID.matcher(value).matches()
                ? value : UUID.randomUUID().toString();
    }
}
