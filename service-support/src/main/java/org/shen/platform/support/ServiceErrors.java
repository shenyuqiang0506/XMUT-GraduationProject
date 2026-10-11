package org.shen.platform.support;

import feign.FeignException;
import org.shen.platform.contracts.ApiResponse;
import org.shen.platform.contracts.TraceIds;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ServiceErrors {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> validation() {
        return ResponseEntity.badRequest().body(ApiResponse.error(
                "VALIDATION_ERROR", "请求参数不符合要求", MDC.get(TraceIds.MDC_KEY)));
    }
    @ExceptionHandler(FeignException.class)
    ResponseEntity<ApiResponse<Void>> dependency() {
        return ResponseEntity.status(503).body(ApiResponse.error(
                "SERVICE_UNAVAILABLE", "依赖服务暂不可用", MDC.get(TraceIds.MDC_KEY)));
    }
}
