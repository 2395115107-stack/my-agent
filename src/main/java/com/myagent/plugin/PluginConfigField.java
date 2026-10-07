package com.myagent.plugin;

/**
 * 插件配置字段模式:名称、展示标签、类型、默认值与数字边界。
 * PluginService 按它校验配置,前端按它渲染表单 —— 同一份声明两处消费。
 */
public record PluginConfigField(String name, String label, Type type, Object defaultValue,
                                Double min, Double max, String hint) {

    public enum Type {TEXT, NUMBER, BOOLEAN}

    public static PluginConfigField text(String name, String label, String defaultValue, String hint) {
        return new PluginConfigField(name, label, Type.TEXT, defaultValue, null, null, hint);
    }

    public static PluginConfigField number(String name, String label, double defaultValue,
                                           double min, double max, String hint) {
        return new PluginConfigField(name, label, Type.NUMBER, defaultValue, min, max, hint);
    }
}
