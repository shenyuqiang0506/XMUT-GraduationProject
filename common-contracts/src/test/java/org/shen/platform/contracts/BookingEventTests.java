package org.shen.platform.contracts;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 消息不信任“能解析 JSON”这一条件，校验租户、收件人、事件状态和必需业务字段。 */
class BookingEventTests {
    private BookingEvent event(String type,String scope,Integer version,List<String> recipients,Map<String,Object> payload){
        return new BookingEvent(UUID.randomUUID().toString(),type,type.startsWith("WAITLIST")?"WAITLIST":"APPLICATION","1",version,"2026-10-13T08:00:00+08:00",recipients,payload,"trace-test-0001",scope);
    }
    @Test void validApplicationEventAndScopeIsolation(){
        var e=event("APPLICATION_APPROVED","dev",1,List.of("1"),Map.of("status","APPROVED"));
        assertThatCode(() -> e.validate("dev")).doesNotThrowAnyException();
        assertThatThrownBy(() -> e.validate("prod")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void missingVersionAndStateMismatchAreRejected(){
        assertThatThrownBy(() -> event("APPLICATION_APPROVED","dev",null,List.of("1"),Map.of("status","APPROVED")).validate("dev")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> event("APPLICATION_APPROVED","dev",1,List.of("1"),Map.of("status","CANCELLED")).validate("dev")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void duplicateAndOverflowRecipientsAreRejected(){
        assertThatThrownBy(() -> event("APPLICATION_SUBMITTED","dev",0,List.of("1","1"),Map.of("status","PENDING_APPROVAL")).validate("dev")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> event("APPLICATION_SUBMITTED","dev",0,List.of("9999999999999999999"),Map.of("status","PENDING_APPROVAL")).validate("dev")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void OfferAndConfirmationNeedDeadlineOrApplication(){
        assertThatThrownBy(() -> event("WAITLIST_OFFERED","dev",1,List.of("1"),Map.of("status","OFFERED")).validate("dev")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> event("WAITLIST_CONFIRMED","dev",2,List.of("1"),Map.of("status","CONFIRMED")).validate("dev")).isInstanceOf(IllegalArgumentException.class);
    }
}
