package com.myagent.engine;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.myagent.config.ModelFactory;
import com.myagent.plugin.PluginService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * 智能体基类:继承 + @Component + 容器启动后自动注册。
 * 对齐 Phoenix AbstractReactAgent.java:28-117(SmartInitializingSingleton 是"自动注册"的全部秘密)。
 *
 * 通用切面(记忆 Hook / 用量 Interceptor)由基类统一装配,子类只关心身份、提示词和工具。
 * 工具集 = 子类团队工具 + 已启用插件贡献的工具(全局作用域,见 PluginService);
 * 插件启停/改配置后由 AgentRebuildService 调 rebuildAndRegister() 按当前状态重建并覆盖注册。
 */
public abstract class AbstractTeamAgent implements SmartInitializingSingleton {

    protected final AgentRegistry registry;
    protected final RedisSaver saver;
    protected final ModelFactory modelFactory;
    protected final UsageInterceptor usageInterceptor;
    protected final PluginService pluginService;

    protected AbstractTeamAgent(AgentRegistry registry, RedisSaver saver,
                                ModelFactory modelFactory, UsageInterceptor usageInterceptor,
                                PluginService pluginService) {
        this.registry = registry;
        this.saver = saver;
        this.modelFactory = modelFactory;
        this.usageInterceptor = usageInterceptor;
        this.pluginService = pluginService;
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
                .methodTools(mergedTools())
                .interceptors(List.of(usageInterceptor))
                .saver(saver)
                .build();
    }

    /** 团队工具 + 启用插件的工具;插件全关时保持原数组,不多不少。 */
    private Object[] mergedTools() {
        Object[] own = tools();
        List<Object> pluginTools = pluginService.toolBeans();
        if (pluginTools.isEmpty()) {
            return own;
        }
        return Stream.concat(Arrays.stream(own), pluginTools.stream()).toArray();
    }

    /** 插件启停/配置变更后的热重建入口:重建并覆盖注册,进行中的回合继续用旧引用跑完。 */
    public void rebuildAndRegister() {
        registry.register(sn(), build());
    }

    @Override
    public void afterSingletonsInstantiated() {
        registry.register(sn(), build());
    }
}
