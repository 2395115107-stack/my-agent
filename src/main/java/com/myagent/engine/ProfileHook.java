package com.myagent.engine;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.messages.UpdatePolicy;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 画像注入 Hook(长期记忆"读"路径的挂点)。
 * 对齐 Phoenix AbstractCombinedDbHook.java:22-111:beforeModel 读 metadata,
 * 把画像拼进 SystemMessage,REPLACE 整表消息。
 *
 * MVP 从 metadata.profileText 取画像文本;接 pgvector/画像表时只改 loadProfile。
 */
public class ProfileHook extends MessagesModelHook {

    @Override
    public String getName() {
        return "profile_hook";
    }

    @Override
    public AgentCommand beforeModel(List<Message> previousMessages, RunnableConfig config) {
        String profileText = loadProfile(config);
        if (profileText == null || profileText.isBlank()) {
            return new AgentCommand(previousMessages);
        }
        String contextInfo = "【运行者上下文】\n" + profileText + "\n";
        SystemMessage enhanced;
        Optional<Message> existing = previousMessages.stream()
                .filter(m -> m instanceof SystemMessage)
                .findFirst();
        if (existing.isPresent()) {
            enhanced = new SystemMessage(((SystemMessage) existing.get()).getText() + "\n\n" + contextInfo);
        } else {
            enhanced = new SystemMessage(contextInfo);
        }
        List<Message> newMessages = new ArrayList<>();
        newMessages.add(enhanced);
        previousMessages.stream().filter(m -> !(m instanceof SystemMessage)).forEach(newMessages::add);
        return new AgentCommand(newMessages, UpdatePolicy.REPLACE);
    }

    /** 扩展点:接用户画像表 / 向量检索(对齐 Phoenix LoginUserAgentInterceptor.addHistoryMemory)。 */
    protected String loadProfile(RunnableConfig config) {
        return (String) config.metadata("profileText").orElse(null);
    }
}
