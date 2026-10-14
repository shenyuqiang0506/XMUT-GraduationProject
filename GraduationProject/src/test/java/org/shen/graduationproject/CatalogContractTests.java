package org.shen.graduationproject;

import java.util.*;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.shen.graduationproject.application.ResourceCatalog;
import org.shen.graduationproject.application.CatalogImports;
import org.shen.graduationproject.domain.*;
import org.shen.graduationproject.infrastructure.*;
import org.shen.platform.contracts.AuthActor;
import org.shen.platform.support.BusinessException;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 直接验证写入与查询契约，以及不启动 prod 的环境边界。 */
class CatalogContractTests {
    private CatalogCommands.Resource equipment(String type,String mode) {
        return new ObjectMapper().convertValue(Map.of("resourceType","EQUIPMENT","sourceSystem","TEST","sourceId","1",
                "name","测试设备","sourceBorrowable","UNKNOWN","platformStatus","DRAFT","dataMode",mode,
                "assetCode","001","equipmentType",type),CatalogCommands.Resource.class);
    }
    private DataModes modes(String... profiles) {
        var environment=new MockEnvironment();environment.setActiveProfiles(profiles);return new DataModes(environment);
    }
    @Test void localizedEquipmentTypesWorkInCatalogAndSearch() {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();
            for(String type:List.of("话筒","无线 话筒","MICROPHONE","CAMERA-1")) {
                assertThat(validator.validate(equipment(type,"DEMO"))).isEmpty();
                assertThat(validator.validate(new AvailabilityCommands.EquipmentRequirement(type,1))).isEmpty();
                var row=Map.<String,Object>of("id",1L,"approval_group_id",1L,"campus","测试校区","equipment_type",type);
                assertThat(new EquipmentIndex(List.of(row)).select(row,type,1)).containsExactly("1");
            }
            assertThat(validator.validate(new AvailabilityCommands.EquipmentRequirement(" ",1))).isNotEmpty();
            assertThat(validator.validate(new AvailabilityCommands.EquipmentRequirement("型".repeat(33),1))).isNotEmpty();
        }
    }
    @Test void prodDominatesDevAndTestProfiles() {
        for(String[] profiles:List.of(new String[]{"prod"},new String[]{"prod","dev"},new String[]{})) {
            var modes=modes(profiles);modes.check("REAL");assertThat(modes.demoAllowed()).isFalse();
            assertThatThrownBy(() -> modes.check("DEMO")).isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.status()).isEqualTo(403));
        }
        modes("dev").check("DEMO");modes("test").check("DEMO");
    }
    @Test void prodCatalogRejectsDemoBeforeAnyDatabaseAccess() {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var sql=mock(org.springframework.jdbc.core.JdbcTemplate.class);
            var catalog=new ResourceCatalog(null,null,sql,null,null,null,new ObjectMapper(),factory.getValidator(),modes("prod"));
            assertThatThrownBy(() -> catalog.validate(equipment("话筒","DEMO"))).isInstanceOf(BusinessException.class);
            catalog.validate(equipment("话筒","REAL"));verifyNoInteractions(sql);
        }
    }
    @Test void prodVisibilityFiltersDemoEvenForSystemAdministrator() {
        var sql=mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var actor=new AuthActor("1","1","test","测试","ACTIVE",0,List.of("SYSTEM_ADMIN"));
        assertThat(new CatalogAccess(sql,modes("prod")).visibleSql(actor)).isEqualTo("r.data_mode='REAL'");
        assertThat(new CatalogAccess(sql,modes("dev")).visibleSql(actor)).isEqualTo("1=1");
    }
    @Test void prodImportPreviewAndPreviouslySavedBatchCannotBypassModeGuard() {
        var sql=mock(org.springframework.jdbc.core.JdbcTemplate.class);var access=mock(CatalogAccess.class);
        var actor=new AuthActor("1","1","test","测试","ACTIVE",0,List.of("SYSTEM_ADMIN"));
        var storage=mock(ImportStorage.class);var idempotency=mock(CatalogIdempotency.class);
        var imports=new CatalogImports(null,sql,access,idempotency,storage,null,null,new ObjectMapper(),null,modes("prod"));
        assertThatThrownBy(() -> imports.preview(actor,"test-key",null,"RESOURCE_CATALOG","TEST","DEMO","{}","{}"))
                .isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.status()).isEqualTo(403));
        when(access.system(actor)).thenReturn(true);
        when(sql.queryForList("SELECT * FROM import_batch WHERE id=?",1L))
                .thenReturn(List.of(Map.of("operator_id",1L,"data_mode","DEMO","import_type","RESOURCE_CATALOG","status","COMMITTED")));
        assertThatThrownBy(() -> imports.commit(actor,"test-key",1L))
                .isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.status()).isEqualTo(403));
        verifyNoInteractions(storage,idempotency);
    }
}
