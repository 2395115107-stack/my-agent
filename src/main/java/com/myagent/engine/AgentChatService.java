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
 * Agent 调用统一入口:塞元数据 -> 取注册表 -> 流式执行;直聊与团队回合统一落服务端历史。
 * 对齐 Phoenix ReactAgentComponent.java:63-96;threadId = sessionId 挂 Redis 检查点。
 */
@Service
@RequiredArgsConstructor
public class AgentChatService {

    private final AgentRegistry registry;
    private final ChatStore chatStore;

    public Flux<NodeOutput> stream(String sn, String message, AgentProfile profile) {
        ReactAgent agent = (ReactAgent) registry.load(sn);
        String threadId = profile.getSessionId();
        RunnableConfig config = RunnableConfig.builder()
                .threadId(threadId)
                .addMetadata("userId", profile.getUserId())
                .addMetadata("agentSn", sn)
                .addMetadata("sessionId", threadId)
                .addMetadata("message", message)
                .build();
        // 服务端历史(dsh 式):用户消息立即落库并登记会话,助手输出随流累积、完成时落库
        chatStore.append(sn, threadId, "user", message);
        chatStore.ensureSession(sn, threadId, message, profile.getProjectId());
        StringBuilder assistant = new StringBuilder();
        // stream() 声明受检异常(对齐 Phoenix ReactAgentComponent.java:67),响应式入口转错误信号
        return Flux.defer(() -> {
            try {
                return agent.stream(UserMessage.builder().text(message).build(), config);
            } catch (GraphRunnerException e) {
                return Flux.error(e);
            }
        })
        .doOnNext(output -> {
            if (output instanceof com.alibaba.cloud.ai.graph.streaming.StreamingOutput<?> streaming
                    && streaming.chunk() != null && !output.isEND()) {
                assistant.append(streaming.chunk());
            }
        })
        .doOnComplete(() -> chatStore.append(sn, threadId, "assistant", assistant.toString()));
    }
}
