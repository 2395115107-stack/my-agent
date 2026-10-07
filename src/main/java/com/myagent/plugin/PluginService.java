package com.myagent.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.config.SettingService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件运行时:启停 / 配置 / 持久化(tbl_setting)/ 管理视图与只读清单投影。
 * 状态存 tbl_setting 的 {@link #SETTING_KEY}(与模型/治理配置同一持久化通道,重启恢复);
 * Agent 工具集只在重建时读取 {@link #toolBeans()} —— 启停/改配置后由 AgentRebuildService 热重建。
 * 全部写操作先校验后落库,失败不动运行态(与设置页同一纪律)。
 */
@Slf4j
@Service
public class PluginService {

    public static final String SETTING_KEY = "plugin.states";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SettingService settingService;
    private final List<AgentPlugin> plugins;

    /** 单插件运行态:error != null 表示上次启用/恢复时构建工具失败(清单标「启动失败」)。 */
    public record PluginState(boolean enabled, Map<String, Object> config, String error) {
    }

    private final Map<String, PluginState> states = new ConcurrentHashMap<>();

    public PluginService(SettingService settingService, List<AgentPlugin> plugins) {
        this.settingService = settingService;
        this.plugins = List.copyOf(plugins);
    }

    @PostConstruct
    void restore() {
        Map<String, Object> stored;
        try {
            stored = settingService.getJson(SETTING_KEY);
        } catch (Exception e) {
            log.warn("[plugin] 插件状态读取失败,全部按默认关闭启动", e);
            return;
        }
        for (AgentPlugin p : plugins) {
            Object raw = stored.get(p.key());
            if (!(raw instanceof Map<?, ?> rec)) {
                continue; // 未出现过 = 默认关闭
            }
            boolean enabled = Boolean.parseBoolean(String.valueOf(rec.get("enabled")));
            Object cfgRaw = rec.get("config");
            Map<String, Object> cfg = cfgRaw instanceof Map<?, ?> m ? asStringMap(m) : Map.of();
            Map<String, Object> validated;
            try {
                validated = validate(p, cfg);
            } catch (Exception e) {
                if (enabled) {
                    states.put(p.key(), new PluginState(true, Map.of(), "配置无效:" + e.getMessage()));
                    log.warn("[plugin] {} 持久化配置无效,清单将标记启动失败", p.key());
                }
                continue;
            }
            states.put(p.key(), new PluginState(enabled, validated, null));
            if (enabled) {
                try {
                    p.toolBeans(validated);
                } catch (Exception e) {
                    states.put(p.key(), new PluginState(true, validated, rootMessage(e)));
                    log.warn("[plugin] {} 恢复启用失败,清单将标记启动失败", p.key(), e);
                }
            }
        }
        log.info("[plugin] 已加载 {} 个内置插件,启用 {} 个",
                plugins.size(), states.values().stream().filter(PluginState::enabled).count());
    }

    /**
     * 当前启用插件的工具对象。Agent 构建(启动注册 / 插件变更热重建)时并入 methodTools;
     * 单插件构建失败只标记该插件 FAILED 并跳过,不拖垮 Agent 重建。
     */
    public List<Object> toolBeans() {
        List<Object> out = new ArrayList<>();
        for (AgentPlugin p : plugins) {
            PluginState st = states.get(p.key());
            if (st == null || !st.enabled() || st.error() != null) {
                continue;
            }
            try {
                List<Object> beans = p.toolBeans(mergedConfig(p, st.config()));
                if (beans != null) {
                    out.addAll(beans);
                }
            } catch (Exception e) {
                log.warn("[plugin] {} 工具构建失败,本轮不注入", p.key(), e);
                states.put(p.key(), new PluginState(true, st.config(), rootMessage(e)));
            }
        }
        return out;
    }

    // ---------- 管理操作 ----------

    public synchronized Map<String, Object> enable(String key, Map<String, Object> config) {
        AgentPlugin p = require(key);
        PluginState current = states.get(key);
        Map<String, Object> cfg = validate(p, config != null ? config
                : current == null ? null : current.config());
        try {
            List<Object> beans = p.toolBeans(cfg); // 启用前探针:构建失败不落库
            if (beans == null || beans.isEmpty()) {
                throw new IllegalStateException("插件未提供任何工具");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("启用失败:" + rootMessage(e), e);
        }
        states.put(key, new PluginState(true, cfg, null));
        persist();
        log.info("[plugin] 已启用 {} ({}),其工具将注入所有 Agent", p.title(), key);
        return view(key);
    }

    public synchronized Map<String, Object> disable(String key) {
        AgentPlugin p = require(key);
        PluginState current = states.getOrDefault(key, new PluginState(false, Map.of(), null));
        states.put(key, new PluginState(false, current.config(), null));
        persist();
        log.info("[plugin] 已停用 {} ({})", p.title(), key);
        return view(key);
    }

    /** 保存配置:停用态只落库;启用态先探针再落库,失败保留原配置。 */
    public synchronized Map<String, Object> updateConfig(String key, Map<String, Object> config) {
        AgentPlugin p = require(key);
        PluginState current = states.getOrDefault(key, new PluginState(false, Map.of(), null));
        Map<String, Object> cfg = validate(p, config != null ? config : current.config());
        if (current.enabled()) {
            try {
                p.toolBeans(cfg);
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("保存失败:" + rootMessage(e), e);
            }
        }
        states.put(key, new PluginState(current.enabled(), cfg, current.error()));
        persist();
        return view(key);
    }

    // ---------- 投影 ----------

    /** 管理视图(侧栏插件页):卡片/详情/配置表单共用。 */
    public Map<String, Object> view(String key) {
        AgentPlugin p = require(key);
        PluginState st = states.get(key);
        Map<String, Object> out = baseMeta(p);
        out.put("enabled", st != null && st.enabled());
        out.put("error", st == null ? null : st.error());
        out.put("config", mergedConfig(p, st == null ? Map.of() : st.config()));
        out.put("configFields", fieldsProjection(p));
        out.put("tools", toolMeta(p, mergedConfig(p, st == null ? Map.of() : st.config())));
        return out;
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentPlugin p : plugins) {
            out.add(view(p.key()));
        }
        return out;
    }

    /** 只读清单(设置 → 内置插件):含运行状态,不含任何写入口。 */
    public List<Map<String, Object>> inventory() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentPlugin p : plugins) {
            PluginState st = states.get(p.key());
            boolean enabled = st != null && st.enabled();
            String error = st == null ? null : st.error();
            Map<String, Object> row = baseMeta(p);
            row.put("status", error != null ? "FAILED" : enabled ? "ACTIVE" : "DISABLED");
            row.put("error", error);
            row.put("scope", "全局");
            row.put("tools", toolMeta(p, mergedConfig(p, st == null ? Map.of() : st.config())));
            out.add(row);
        }
        return out;
    }

    // ---------- 内部 ----------

    private Map<String, Object> baseMeta(AgentPlugin p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", p.key());
        out.put("title", p.title());
        out.put("description", p.description());
        out.put("version", p.version());
        out.put("experimental", p.experimental());
        out.put("moduleId", p.getClass().getName());
        return out;
    }

    /** 配置字段模式投影(前端表单渲染用)。 */
    private static List<Map<String, Object>> fieldsProjection(AgentPlugin p) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PluginConfigField f : p.configFields()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", f.name());
            m.put("label", f.label());
            m.put("type", f.type().name().toLowerCase());
            m.put("defaultValue", f.defaultValue());
            m.put("min", f.min());
            m.put("max", f.max());
            m.put("hint", f.hint());
            out.add(m);
        }
        return out;
    }

    private AgentPlugin require(String key) {
        return plugins.stream().filter(p -> p.key().equals(key)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("插件不存在: " + key));
    }

    /** 按字段模式校验:类型/边界/未知字段;缺省回落默认值。校验失败的 key 不会落库。 */
    private Map<String, Object> validate(AgentPlugin p, Map<String, Object> config) {
        Map<String, Object> input = config == null ? Map.of() : config;
        Map<String, Object> out = new LinkedHashMap<>();
        for (PluginConfigField f : p.configFields()) {
            Object raw = input.containsKey(f.name()) ? input.get(f.name()) : f.defaultValue();
            switch (f.type()) {
                case TEXT -> {
                    String v = raw == null ? "" : String.valueOf(raw).trim();
                    if (v.isEmpty()) {
                        v = String.valueOf(f.defaultValue());
                    }
                    if (v.length() > 500) {
                        throw new IllegalArgumentException(f.label() + " 过长(最多 500 字符)");
                    }
                    out.put(f.name(), v);
                }
                case NUMBER -> {
                    Double d = toNumber(raw);
                    if (d == null) {
                        d = toNumber(f.defaultValue());
                    }
                    if (d == null) {
                        throw new IllegalArgumentException(f.label() + " 必须是有效数字");
                    }
                    if ((f.min() != null && d < f.min()) || (f.max() != null && d > f.max())) {
                        throw new IllegalArgumentException(f.label() + " 必须在 " + f.min() + " 到 " + f.max() + " 之间");
                    }
                    out.put(f.name(), d);
                }
                case BOOLEAN -> {
                    Boolean b = toBoolean(raw);
                    out.put(f.name(), b != null ? b : Boolean.TRUE.equals(toBoolean(f.defaultValue())));
                }
            }
        }
        for (String name : input.keySet()) {
            if (p.configFields().stream().noneMatch(f -> f.name().equals(name))) {
                throw new IllegalArgumentException("未知配置字段:" + name);
            }
        }
        return out;
    }

    /** 已存配置叠加字段默认值,前端表单直接可用。 */
    private Map<String, Object> mergedConfig(AgentPlugin p, Map<String, Object> stored) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (PluginConfigField f : p.configFields()) {
            Object v = stored.get(f.name());
            out.put(f.name(), v != null ? v : f.defaultValue());
        }
        return out;
    }

    private void persist() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (AgentPlugin p : plugins) {
            PluginState st = states.getOrDefault(p.key(), new PluginState(false, Map.of(), null));
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("enabled", st.enabled());
            rec.put("config", st.config());
            snapshot.put(p.key(), rec);
        }
        settingService.putJson(SETTING_KEY, snapshot);
    }

    /** @Tool 方法名 + 描述(清单与插件页展示工具清单)。 */
    private List<Map<String, String>> toolMeta(AgentPlugin p, Map<String, Object> config) {
        List<Map<String, String>> out = new ArrayList<>();
        try {
            for (Object bean : p.toolBeans(config)) {
                for (Method m : bean.getClass().getMethods()) {
                    Tool t = m.getAnnotation(Tool.class);
                    if (t != null) {
                        out.add(Map.of("name", m.getName(), "description", t.description()));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[plugin] {} 工具元信息读取失败", p.key(), e);
        }
        return out;
    }

    private static Double toNumber(Object raw) {
        if (raw instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? d : null;
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                double d = Double.parseDouble(s.trim());
                return Double.isFinite(d) ? d : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Boolean toBoolean(Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof String s) {
            if ("true".equalsIgnoreCase(s.trim())) return true;
            if ("false".equalsIgnoreCase(s.trim())) return false;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String m = cur.getMessage();
        return m == null || m.isBlank() ? cur.getClass().getSimpleName() : m;
    }
}
