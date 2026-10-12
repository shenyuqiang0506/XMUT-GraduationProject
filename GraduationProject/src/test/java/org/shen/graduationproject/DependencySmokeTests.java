package org.shen.graduationproject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import jakarta.validation.Validation;
import jakarta.validation.constraints.Min;
import cn.hutool.core.util.StrUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 依赖装配的快速冒烟测试，不访问真实业务库。
 * 验证 Lombok 编译生成代码、Hutool、Excel 读写、Bean Validation 与 BCrypt 能正常调用。
 * 通过这些测试只说明依赖可用，不能替代正式业务权限或真实数据库事务验收。
 */
class DependencySmokeTests {
    record Request(@Min(1) int participants) {}

    @Getter
    @RequiredArgsConstructor
    static class UtilityDependencyProbe {
        private final String name;
    }

    @Test
    void lombokGeneratedMembersAndHutoolAreAvailable() {
        var probe = new UtilityDependencyProbe("校园资源预约");
        assertThat(probe.getName()).isEqualTo("校园资源预约");
        assertThat(StrUtil.isBlank(probe.getName())).isFalse();
        assertThat(StrUtil.isBlank(" \t\n")).isTrue();
    }

    /**
     * 内存中写出再读回中文 XLSX，验证读写库可用；其中名称只是测试样例。
     */
    @Test
    void excelWorkbookRoundTrips() throws Exception {
        byte[] data;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            workbook.createSheet("resources").createRow(0).createCell(0).setCellValue("演示场地");
            workbook.write(output);
            data = output.toByteArray();
        }
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue())
                    .isEqualTo("演示场地");
        }
    }

    @Test
    void validationRejectsInvalidParticipantCount() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new Request(0))).hasSize(1);
            assertThat(validator.validate(new Request(1))).isEmpty();
        }
    }

    @Test
    void passwordEncoderVerifiesAndRejectsCredentials() {
        var encoder = new BCryptPasswordEncoder();
        var hash = encoder.encode("smoke-test-password");
        assertThat(encoder.matches("smoke-test-password", hash)).isTrue();
        assertThat(encoder.matches("wrong-password", hash)).isFalse();
    }
}
