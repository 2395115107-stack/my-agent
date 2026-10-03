package com.myagent.engine;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.myagent.config.ModelFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.util.List;

/**
 * 智能体基类:继承 + @Component + 容器启动后自动注册。
 * 对齐 Phoenix AbstractReactAgent.java:28-117(SmartInitializingSingleton 是"自动注册"的全部秘密)。
 *
 * 通用切面(记忆 Hook / 用量 Interceptor)由基类统一装配,子类只关心身份、提示词和工具。
 */
public abstract class AbstractTeamAgent implements SmartInitializingSingleton {

    protected final AgentRegistry registry;
    protected final RedisSaver saver;
    protected final ModelFactory modelFactory;
    protected final UsageInterceptor usageInterceptor;

    protected AbstractTeamAgent(AgentRegistry registry, RedisSaver saver,
                                ModelFactory modelFactory, UsageInterceptor usageInterceptor) {
        this.registry = registry;
        this.saver = saver;
        this.modelFactory = modelFactory;
        this.usageInterceptor = usageInterceptor;
    }

    /** 唯一标识,注册主键(= 团队花名册里的 sn,纪律:工具参数一律用 sn)。 */
    public abstract String sn();

    /** 系统提示词(classpath 资源内容或运行时生成)。 */
    public abstract String systemPrompt();

    /** 额外工具对象(方法上标 @Tool);Leader 传 LeaderTeamTools,成员传 MemberTeamTools。 */
    protected abstract Object[] tools();

    /** 预留:子类追加自己的 Hook(摘要、限调用次数等)。 */
    protected List<Object> extraHooks() {
        return List.of();
    }

    protected ReactAgent build() {
        ChatModel model = modelFactory.chatModel();
        return ReactAgent.builder()
                .name(sn())
                .model(model)
                .systemPrompt(systemPrompt())
                .methodTools(tools())
                .interceptors(List.of(usageInterceptor))
                .saver(saver)
                .build();
    }

    @Override
    public void afterSingletonsInstantiated() {
        registry.register(sn(), build());
    }
}
