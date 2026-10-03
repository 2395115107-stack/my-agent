package com.myagent.config;

import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 短期记忆(图检查点):Redis,threadId = 会话/成员 turn 标识。
 * 写法对齐 Phoenix GraphCheckpointConfig.java:35-56(RedisSaver + Redisson);
 * 序列化器参数以实际引入的 graph-core 版本 API 为准(Phoenix 用 SpringAIJacksonStateSerializer)。
 */
@Configuration
public class GraphCheckpointConfig {

    @Bean
    public RedisSaver redisSaver(RedissonClient redissonClient) {
        return RedisSaver.builder()
                .redisson(redissonClient)
                .build();
    }

    @Bean
    public SaverConfig saverConfig(RedisSaver redisSaver) {
        return SaverConfig.builder()
                .register(redisSaver)
                .build();
    }
}
