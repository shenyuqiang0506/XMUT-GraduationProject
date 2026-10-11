package org.shen.ai.m1;

import java.util.Map;
import org.shen.platform.contracts.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Profile("m1")
@FeignClient(name = "booking-service")
public interface BookingClient {
    @GetMapping("/internal/m1/resources/{id}")
    ApiResponse<Map<String, Object>> resource(@PathVariable String id);
}
