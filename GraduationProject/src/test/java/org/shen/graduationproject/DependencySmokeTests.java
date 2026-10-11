package org.shen.graduationproject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import jakarta.validation.Validation;
import jakarta.validation.constraints.Min;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class DependencySmokeTests {
    record Request(@Min(1) int participants) {}

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
