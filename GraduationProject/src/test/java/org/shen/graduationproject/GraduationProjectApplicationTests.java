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
