package org.shen.graduationproject;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * test profile 下的预约服务基础装配验证。
 * 查询测试数据库并确认 MyBatis、Redis、RabbitMQ 连接工厂已注入，不证明外部 Redis 或队列实际可达。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GraduationProjectApplicationTests {
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SqlSessionFactory sqlSessionFactory;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    RabbitTemplate rabbit;

    @Test
    void contextLoadsAndDataAccessIsWired() {
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        assertThat(sqlSessionFactory.getConfiguration().getEnvironment().getDataSource()).isNotNull();
        assertThat(redis.getConnectionFactory()).isNotNull();
        assertThat(rabbit.getConnectionFactory()).isNotNull();
    }
}
