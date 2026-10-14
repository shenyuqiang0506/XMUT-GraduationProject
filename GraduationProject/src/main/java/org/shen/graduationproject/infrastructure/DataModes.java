package org.shen.graduationproject.infrastructure;

import lombok.RequiredArgsConstructor;
import org.shen.platform.support.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * 统一目录、导入和预约的数据模式边界，独立于应用服务，避免相互注入形成循环。
 * prod 即使误带 dev/test profile 也拒绝 DEMO；部署环境与数据来源真实性分别判断。
 */
@Component
@Profile("business")
@RequiredArgsConstructor
public class DataModes {
    private final Environment environment;

    public boolean demoAllowed() {
        return !environment.acceptsProfiles(Profiles.of("prod"))
                && environment.acceptsProfiles(Profiles.of("dev", "test"));
    }

    public void check(String mode) {
        if (mode == null || !java.util.Set.of("REAL", "DEMO").contains(mode))
            throw new BusinessException(400, "VALIDATION_ERROR", "需要明确指定 REAL 或 DEMO");
        if ("DEMO".equals(mode) && !demoAllowed())
            throw new BusinessException(403, "FORBIDDEN", "演示数据仅允许在 dev/test 环境使用");
    }
}
