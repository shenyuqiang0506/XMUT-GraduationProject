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

    @Test
    void nacosImportsConfigAndRegistersService() {
        assertThat(environment.getProperty("platform.environment")).isEqualTo("dev");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(discovery.getInstances("booking-service")).isNotEmpty());
    }
}
