package org.shen.identity.domain;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.shen.platform.support.BusinessException;
import static org.junit.jupiter.api.Assertions.*;

/** 人员名单边界测试：保留编号、拒绝提权、保护密码原文并阻断非法整批输入。 */
class PersonnelAccountsTests {
    private AuthCommands.PersonnelRow row(String number, String role, String password) {
        return new AuthCommands.PersonnelRow(number, " 测试人员 ", role, password);
    }

    @Test
    void preservesLeadingZerosAndPasswordWhileTrimmingPersonnelFields() {
        var result = PersonnelAccounts.prepare(new AuthCommands.PersonnelImport(List.of(
                row(" 000123 ", "STUDENT", "  password-123  "), row("T0001", "TEACHER", "password-456"))));
        assertTrue(result.errors().isEmpty());
        assertEquals("000123", result.users().getFirst().username());
        assertEquals("测试人员", result.users().getFirst().displayName());
        assertEquals("  password-123  ", result.users().getFirst().initialPassword());
        assertEquals(Set.of("TEACHER"), result.users().get(1).roleCodes());
    }

    @Test
    void detectsDuplicateNumbersAcrossStudentAndTeacherRows() {
        var result = PersonnelAccounts.prepare(new AuthCommands.PersonnelImport(List.of(
                row("000123", "STUDENT", "password-123"), row(" 000123 ", "TEACHER", "password-123"))));
        assertEquals("DUPLICATE_NUMBER", result.errors().getFirst().code());
        assertEquals(2, result.errors().getFirst().row());
    }

    @Test
    void refusesManagerRolesAndReportsSafeRowErrors() {
        var result = PersonnelAccounts.prepare(new AuthCommands.PersonnelImport(List.of(
                row("000123", "SYSTEM_ADMIN", "password-123"), row("000124", "RESOURCE_ADMIN", "password-123"))));
        assertEquals(2, result.errors().size());
        assertTrue(result.users().isEmpty());
        assertFalse(result.errors().toString().contains("password-123"));
    }

    @Test
    void rejectsOversizedUtf8PasswordsAndNullRows() {
        var result = PersonnelAccounts.prepare(new AuthCommands.PersonnelImport(Arrays.asList(
                null, row("000123", "TEACHER", "密".repeat(25)))));
        assertEquals(List.of("REQUIRED", "INVALID_PASSWORD"), result.errors().stream().map(PersonnelAccounts.Error::code).toList());
    }

    @Test
    void rejectsMissingNameAndMalformedNumber() {
        var result = PersonnelAccounts.prepare(new AuthCommands.PersonnelImport(List.of(
                new AuthCommands.PersonnelRow("12 34", " ", "TEACHER", "password-123"))));
        assertEquals(List.of("INVALID_NUMBER", "INVALID_NAME"), result.errors().stream().map(PersonnelAccounts.Error::code).toList());
    }

    @Test
    void limitsBatchBeforePasswordWork() {
        assertThrows(BusinessException.class, () -> PersonnelAccounts.prepare(new AuthCommands.PersonnelImport(List.of())));
        assertThrows(BusinessException.class, () -> PersonnelAccounts.prepare(new AuthCommands.PersonnelImport(
                Collections.nCopies(51, row("000123", "STUDENT", "password-123")))));
    }
}
