package com.sky.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.StringUtils;

@Configuration
public class RedissonConfiguration {
    @Bean(destroyMethod = "shutdown")
    @Lazy
    public RedissonClient redissonClient(RedisProperties redisProperties) {
        Config config = new Config();
        var server = config.useSingleServer()
                .setAddress("redis://" + redisProperties.getHost() + ":" + redisProperties.getPort())
                .setDatabase(redisProperties.getDatabase())
                .setConnectTimeout(2000)
                .setTimeout(3000);
        if (StringUtils.hasText(redisProperties.getPassword())) {
            server.setPassword(redisProperties.getPassword());
        }
        return Redisson.create(config);
    }
}
