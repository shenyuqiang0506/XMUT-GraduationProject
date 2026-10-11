package org.shen.graduationproject.m1;

import java.util.Map;
import org.shen.platform.contracts.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;

@Profile("m1")
@FeignClient(name = "identity-service")
public interface IdentityClient {
    @GetMapping("/internal/m1/status")
    ApiResponse<Map<String, String>> status();
}
