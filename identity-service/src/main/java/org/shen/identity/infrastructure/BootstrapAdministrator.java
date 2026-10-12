package org.shen.identity.infrastructure;

import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 开发与测试空库的首次管理员初始化。
 * 显式启用 bootstrap-admin 才运行；拒绝 prod，也不覆盖非空库的账号或密码。
 * 初始化与普通账号管理共用守卫行，避免多个实例同时创建管理员。
 */
@Component
@Profile("bootstrap-admin & business")
class BootstrapAdministrator implements ApplicationRunner {
    private final IdentityRepository repo;
    private final PasswordEncoder passwords;
    private final TransactionTemplate transactions;
    private final Environment environment;
    private final String username, password;

    BootstrapAdministrator(IdentityRepository repo, PasswordEncoder passwords, TransactionTemplate transactions, Environment environment,
                           @Value("${AUTH_ADMIN_USERNAME}") String username, @Value("${AUTH_ADMIN_PASSWORD}") String password) {
        this.repo = repo;
        this.passwords = passwords;
        this.transactions = transactions;
        this.environment = environment;
        this.username = username;
        this.password = password;
    }

    public void run(ApplicationArguments arguments) {
        if (!environment.acceptsProfiles(Profiles.of("dev", "test")) || environment.acceptsProfiles(Profiles.of("prod")))
            throw new IllegalStateException("Bootstrap admin is dev/test only");
        if (!username.matches("[A-Za-z0-9_.-]{3,64}") || password.length() < 24 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw new IllegalStateException("Explicit strong bootstrap credentials required");
        transactions.executeWithoutResult(tx -> {
            repo.sql().queryForObject("SELECT id FROM identity_admin_guard WHERE id=1 FOR UPDATE", Integer.class);
            var existing = repo.username(username, true);
            if (existing != null && "ACTIVE".equals(existing.status()) && repo.roles(existing.id()).contains("SYSTEM_ADMIN"))
                return;
            if (repo.sql().queryForObject("SELECT COUNT(*) FROM `user`", Long.class) != 0)
                throw new IllegalStateException("Non-empty identity database; refusing to create or replace bootstrap administrator");
            long id = repo.createUser(username, passwords.encode(password), "开发系统管理员", Set.of("SYSTEM_ADMIN"));
            repo.audit(null, "BOOTSTRAP_ADMIN", id, null, "ACTIVE");
        });
    }
}
