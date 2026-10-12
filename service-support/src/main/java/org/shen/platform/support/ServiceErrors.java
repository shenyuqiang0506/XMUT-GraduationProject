package org.shen.platform.support;

import feign.FeignException;
import org.shen.platform.contracts.ApiResponse;
import org.shen.platform.contracts.TraceIds;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Servlet 业务服务统一异常出口。
 * 将参数错误、业务拒绝和依赖故障转换为同一响应结构，不返回底层堆栈或 SQL。
 */
@RestControllerAdvice
public class ServiceErrors {
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiResponse<Void>> invalidType() {
        return validation();
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<ApiResponse<Void>> unexpected() {
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
    ResponseEntity<ApiResponse<Void>> dependency() {
        return ResponseEntity.status(503).body(ApiResponse.error(
                "SERVICE_UNAVAILABLE", "依赖服务暂不可用", MDC.get(TraceIds.MDC_KEY)));
    }
}
