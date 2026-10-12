package org.shen.graduationproject;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 显式启用后才运行的本机 dev 基础设施集成测试。
 * 使用真实 MySQL、Redis、RabbitMQ 和 Nacos；只创建带随机标识的临时对象并清理。
 * 这些检查验证连通与基础事务，不替代多资源预约并发或消息恢复验收。
 */
@EnabledIfEnvironmentVariable(named = "XMUT_INFRA_TEST", matches = "true")
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InfrastructureIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired StringRedisTemplate redis;
    @Autowired RabbitTemplate rabbit;
    @Autowired DiscoveryClient discovery;
    @Autowired Environment environment;

    /**
     * 新建随机临时 InnoDB 表，主动回滚一条插入并确认无记录；finally 仅删除本次表。
     */
    @Test
    void mysqlUsesRealInnoDbAndRollsBack() {
        assertThat(jdbc.queryForObject("SELECT @@version", String.class)).startsWith("8.4.");
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("booking_db");
        assertThat(jdbc.queryForObject("SELECT @@session.transaction_isolation", String.class)).isEqualTo("READ-COMMITTED");
        String table = "dependency_smoke_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE TABLE " + table + " (id INT PRIMARY KEY) ENGINE=InnoDB");
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.update("INSERT INTO " + table + " VALUES (1)");
                status.setRollbackOnly();
            });
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
        } finally {
            jdbc.execute("DROP TABLE " + table);
        }
    }

    /**
     * 写带随机名和短 TTL 的测试键，验证认证及读写后清理，避免污染既有缓存。
     */
    @Test
    void redisAuthenticatesAndRoundTrips() {
        String key = "xmut:dev:smoke:" + UUID.randomUUID();
        try {
            redis.opsForValue().set(key, "verified", Duration.ofSeconds(30));
            assertThat(redis.opsForValue().get(key)).isEqualTo("verified");
        } finally {
            redis.delete(key);
        }
    }

    /**
     * 使用非持久、独占、自动删除的临时队列验证收发，不使用任何业务队列。
     */
    @Test
    void rabbitAuthenticatesAndRoundTripsInDevVhost() {
        var admin = new RabbitAdmin(rabbit.getConnectionFactory());
        String name = "xmut.dev.smoke." + UUID.randomUUID();
        admin.declareQueue(new Queue(name, false, true, true));
        try {
            rabbit.convertAndSend("", name, "verified");
            assertThat(rabbit.receiveAndConvert(name, 10_000)).isEqualTo("verified");
        } finally {
            admin.deleteQueue(name);
        }
    }

    /**
     * 同时核实导入的 dev 环境标记和服务注册；注册是异步行为，因此使用有限等待。
     */
    @Test
    void nacosImportsConfigAndRegistersService() {
        assertThat(environment.getProperty("platform.environment")).isEqualTo("dev");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(discovery.getInstances("booking-service")).isNotEmpty());
    }
}
