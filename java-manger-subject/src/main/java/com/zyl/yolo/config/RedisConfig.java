package com.zyl.yolo.config;

import com.zyl.yolo.common.RedisUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 配置（String 序列化，兼容队列与缓存场景）
 */
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    public RedisUtils redisUtils(StringRedisTemplate stringRedisTemplate) {
        return new RedisUtils(stringRedisTemplate);
    }
}