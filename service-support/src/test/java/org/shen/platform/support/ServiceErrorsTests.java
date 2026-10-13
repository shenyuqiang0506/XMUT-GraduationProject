package org.shen.platform.support;

import org.junit.jupiter.api.Test;
import org.springframework.dao.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 用真实异常分派验证业务拒绝、暂时性数据库故障与程序错误不会混淆。 */
class ServiceErrorsTests {
    @RestController
    static class Probe {
        @GetMapping("/probe/{kind}") void fail(@PathVariable String kind) {
            switch (kind) {
                case "business" -> throw new BusinessException(422, "INVALID_MANAGER", "管理员资格无效");
                case "connection" -> throw new DataAccessResourceFailureException("测试连接故障");
                case "lock" -> throw new CannotAcquireLockException("测试锁等待");
                case "sql" -> throw new InvalidDataAccessResourceUsageException("测试 SQL 错误");
                default -> throw new NullPointerException("测试程序错误");
            }
        }
    }

    private void check(String kind, int status, String code) throws Exception {
        MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(new ServiceErrors()).build()
                .perform(get("/probe/" + kind)).andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code)).andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test void businessStatusIsPreserved() throws Exception { check("business", 422, "INVALID_MANAGER"); }
    @Test void lostConnectionIsUnavailable() throws Exception { check("connection", 503, "SERVICE_UNAVAILABLE"); }
    @Test void lockFailureIsUnavailable() throws Exception { check("lock", 503, "SERVICE_UNAVAILABLE"); }
    @Test void sqlMistakeIsInternalError() throws Exception { check("sql", 500, "INTERNAL_ERROR"); }
    @Test void programmingMistakeIsInternalError() throws Exception { check("program", 500, "INTERNAL_ERROR"); }
}
