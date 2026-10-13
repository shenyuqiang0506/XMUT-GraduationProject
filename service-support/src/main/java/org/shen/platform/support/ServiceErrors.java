package org.shen.platform.support;

import feign.FeignException;
import org.shen.platform.contracts.ApiResponse;
import org.shen.platform.contracts.TraceIds;
import org.slf4j.MDC;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Servlet 业务服务统一异常出口。
 * 将参数错误、业务拒绝和依赖故障转换为同一响应结构，不返回底层堆栈或 SQL。
 */
@RestControllerAdvice
@Slf4j
public class ServiceErrors {
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiResponse<Void>> invalidType() {
        return validation();
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<ApiResponse<Void>> unexpected(RuntimeException failure) {
        // 未知程序错误不能伪装成依赖不可用；响应隐藏内部细节，日志携带 traceId 供排查。
        log.error("未处理的服务异常，traceId={}", MDC.get(TraceIds.MDC_KEY), failure);
        return ResponseEntity.status(500).body(ApiResponse.error(
                "INTERNAL_ERROR", "服务内部错误，请联系管理员", MDC.get(TraceIds.MDC_KEY)));
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessException.class})
    ResponseEntity<ApiResponse<Void>> databaseUnavailable(RuntimeException failure) {
        // 连接故障、锁等待等暂时性数据库异常返回 503；SQL 编写错误仍走 500。
        log.warn("数据库暂不可用，traceId={}", MDC.get(TraceIds.MDC_KEY), failure);
        return ResponseEntity.status(503).body(ApiResponse.error(
                "SERVICE_UNAVAILABLE", "服务暂不可用", MDC.get(TraceIds.MDC_KEY)));
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiResponse<Void>> business(BusinessException failure) {
        return ResponseEntity.status(failure.status()).body(ApiResponse.error(
                failure.code(), failure.getMessage(), MDC.get(TraceIds.MDC_KEY)));
    }

    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingRequestHeaderException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.web.multipart.MultipartException.class,
            org.springframework.web.method.annotation.HandlerMethodValidationException.class})
    ResponseEntity<ApiResponse<Void>> malformed() {
        return validation();
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> validation() {
        return ResponseEntity.badRequest().body(ApiResponse.error(
                "VALIDATION_ERROR", "请求参数不符合要求", MDC.get(TraceIds.MDC_KEY)));
    }

    @ExceptionHandler(FeignException.class)
    ResponseEntity<ApiResponse<Void>> dependency(FeignException failure) {
        log.warn("依赖调用失败，traceId={}，status={}", MDC.get(TraceIds.MDC_KEY), failure.status());
        return ResponseEntity.status(503).body(ApiResponse.error(
                "SERVICE_UNAVAILABLE", "依赖服务暂不可用", MDC.get(TraceIds.MDC_KEY)));
    }
}
