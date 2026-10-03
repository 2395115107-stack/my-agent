package com.myagent.engine;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Agent 调用统一入口:塞元数据 -> 取注册表 -> 流式执行。
 * 对齐 Phoenix ReactAgentComponent.java:63-96;threadId = sessionId 挂 Redis 检查点。
 */
@Service
@RequiredArgsConstructor
public class AgentChatService {

    private final AgentRegistry registry;

    public Flux<NodeOutput> stream(String sn, String message, AgentProfile profile) {
        ReactAgent agent = (ReactAgent) registry.load(sn);
        RunnableConfig config = RunnableConfig.builder()
                .threadId(profile.getSessionId())
                .addMetadata("userId", profile.getUserId())
                .addMetadata("agentSn", sn)
                .addMetadata("sessionId", profile.getSessionId())
                .addMetadata("message", message)
                .build();
        // stream() 声明受检异常(对齐 Phoenix ReactAgentComponent.java:67),响应式入口转错误信号
        return Flux.defer(() -> {
            try {
                return agent.stream(UserMessage.builder().text(message).build(), config);
            } catch (GraphRunnerException e) {
                return Flux.error(e);
            }
        });
    }
}
