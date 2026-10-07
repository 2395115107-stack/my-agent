package com.myagent.plugin;

import java.util.List;
import java.util.Map;

/**
 * 内置插件 SPI。语义对齐 dsh ui-plugin-manager 的「官方组合包」:随应用附带、默认关闭、无卸载,
 * 只做启停与配置。实现类标 @Component 即被 PluginService 收录;启用后 toolBeans() 返回的
 * @Tool 对象注入所有 Agent(全局作用域),停用即从工具集摘除(经 AgentRebuildService 热重建)。
 */
public interface AgentPlugin {

    /** 稳定标识(卡片 id / 持久化主键),形如 plugin-datetime。 */
    String key();

    /** 展示名(卡片标题)。 */
    String title();

    /** 一句话说明(卡片描述)。 */
    String description();

    default String version() {
        return "0.1.0";
    }

    /** 实验性插件在卡片与清单里带「实验性」标签(dsh 的 experimental 标记)。 */
    default boolean experimental() {
        return false;
    }

    /** 配置字段模式:插件页表单渲染与服务端校验的同一份真相。 */
    default List<PluginConfigField> configFields() {
        return List.of();
    }

    /**
     * 返回携带 @Tool 方法的工具对象。传入的 config 已通过字段校验;
     * 抛出异常视为启用/保存失败(不落库、不改变运行态)。
     */
    List<Object> toolBeans(Map<String, Object> config);
}
