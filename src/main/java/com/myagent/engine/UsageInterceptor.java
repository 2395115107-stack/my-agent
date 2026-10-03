package com.myagent.engine;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 用量统计 Interceptor(模型调用环绕切面)。
 * 对齐 Phoenix LoginUserAgentInterceptor.java:35-72:Redis 1 分钟去重,防止一次会话重复计数;
 * 正式版在此异步落用量表 + 向量检索历史记忆注入(见融合蓝图 2.6 节)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UsageInterceptor extends ModelInterceptor {

    private static final String KEY_PREFIX = "usage:";

    private final StringRedisTemplate redisTemplate;

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        String userId = (String) request.getContext().getOrDefault("userId", "anonymous");
        String agentSn = (String) request.getContext().getOrDefault("agentSn", "unknown");
        String key = KEY_PREFIX + userId + ":" + agentSn;
        Boolean first = redisTemplate.opsForValue().setIfAbsent(key, "1", Duration.ofMinutes(1));
        if (Boolean.TRUE.equals(first)) {
            log.info("[usage] user={} agent={}", userId, agentSn);
            // TODO(P5): 异步落 tbl_agent_user_agent_info 式用量表;向量检索历史记忆注入 request.getMessages()
        }
        return handler.call(request);
    }

    @Override
    public String getName() {
        return "UsageInterceptor";
    }
}
