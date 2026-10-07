package com.myagent.web;

import com.myagent.plugin.AgentRebuildService;
import com.myagent.plugin.PluginService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 插件管理 API(对齐 dsh ui-plugin-manager / plugin-inventory 的 Remote 面):
 * - GET  /api/plugins              管理视图:卡片元信息 + 启停态 + 配置模式 + 工具清单
 * - POST /api/plugins/{key}/enable 启用(可带 config);先校验后落库,失败 400
 * - POST /api/plugins/{key}/disable 停用
 * - PUT  /api/plugins/{key}/config 保存配置(启用态先探针再落库)
 * - GET  /api/plugins/inventory    只读清单(设置 → 内置插件)
 * 状态变更成功后统一触发 Agent 热重建,新回合即用新工具集。
 */
@RestController
@RequestMapping("/api/plugins")
@RequiredArgsConstructor
public class PluginController {

    private final PluginService pluginService;
    private final AgentRebuildService rebuildService;

    @GetMapping
    public List<Map<String, Object>> list() {
        return pluginService.list();
    }

    @PostMapping("/{key}/enable")
    public Map<String, Object> enable(@PathVariable String key,
                                      @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> view = pluginService.enable(key, configOf(body));
        rebuildService.rebuildAll();
        return view;
    }

    @PostMapping("/{key}/disable")
    public Map<String, Object> disable(@PathVariable String key) {
        Map<String, Object> view = pluginService.disable(key);
        rebuildService.rebuildAll();
        return view;
    }

    @PutMapping("/{key}/config")
    public Map<String, Object> updateConfig(@PathVariable String key,
                                            @RequestBody Map<String, Object> body) {
        Map<String, Object> view = pluginService.updateConfig(key, configOf(body));
        rebuildService.rebuildAll(); // 停用态重建是幂等空转;启用态换新配置
        return view;
    }

    @GetMapping("/inventory")
    public Map<String, Object> inventory() {
        return Map.of("scope", "全局", "plugins", pluginService.inventory());
    }

    private static Map<String, Object> configOf(Map<String, Object> body) {
        if (body == null) {
            return null;
        }
        if (body.get("config") instanceof Map<?, ?> config) {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) config;
            return result;
        }
        return null;
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> unknownPlugin(NoSuchElementException error) {
        return Map.of("message", error.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalidPluginRequest(IllegalArgumentException error) {
        return Map.of("message", error.getMessage());
    }
}
