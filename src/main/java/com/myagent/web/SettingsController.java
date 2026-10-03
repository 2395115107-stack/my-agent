package com.myagent.web;

import com.myagent.config.ModelFactory;
import com.myagent.config.SettingService;
import com.myagent.engine.AgentRegistry;
import com.myagent.team.TeamProperties;
import com.myagent.team.mapper.TeamMapper;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设置页后端(对齐 dsh Settings:模型 / 治理 / 系统状态):
 * - 模型配置在线修改并热切换(代理委托到新实例,新回合生效,无需重启),持久化到 tbl_setting;
 * - 团队治理参数热生效(租约/重试/批大小按 turn 读取);
 * - 系统状态:PG / Redis / 注册表 / 团队数。
 */
@Slf4j
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController implements SmartInitializingSingleton, org.springframework.beans.factory.InitializingBean {

    private final ModelFactory modelFactory;
    private final SettingService settingService;
    private final TeamProperties teamProperties;
    private final AgentRegistry registry;
    private final TeamMapper teamMapper;
    private final DataSource dataSource;
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    /** 启动时应用持久化的模型配置(代理委托,晚于 Agent 注册也无需关心顺序)。 */
    @Override
    public void afterSingletonsInstantiated() {
        Map<String, Object> stored = settingService.getJson(SettingService.MODEL_CONFIG);
        if (stored.isEmpty()) {
            return;
        }
        try {
            modelFactory.apply(
                    text(stored, "baseUrl", false),
                    text(stored, "apiKey", true),
                    text(stored, "model", false),
                    number(stored, "temperature"),
                    stored.get("mock") == null ? null : Boolean.parseBoolean(String.valueOf(stored.get("mock"))));
            log.info("[settings] 模型配置已从 tbl_setting 恢复:base-url={} model={} mock={}",
                    modelFactory.getBaseUrl(), modelFactory.getModel(), modelFactory.getMock());
        } catch (Exception e) {
            log.warn("[settings] tbl_setting 中的模型配置无效,使用默认配置启动", e);
        }
    }

    // ---------- 模型 ----------

    @GetMapping("/model")
    public Map<String, Object> getModel() {
        synchronized (modelFactory) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("baseUrl", modelFactory.getBaseUrl());
            out.put("model", modelFactory.getModel());
            out.put("temperature", modelFactory.getTemperature());
            out.put("mock", modelFactory.getMock());
            String key = modelFactory.getApiKey();
            out.put("hasKey", key != null && !key.isBlank() && !"sk-demo".equals(key));
            out.put("keyTail", key == null || key.length() < 8 ? "" : key.substring(key.length() - 4));
            return out;
        }
    }

    @PutMapping("/model")
    public Map<String, Object> updateModel(@RequestBody Map<String, Object> body) {
        String baseUrl = text(body, "baseUrl", false);
        String model = text(body, "model", false);
        // apiKey 留空 = 保留实际生效的 Key;不从浏览器回显或另建副本。
        String apiKey = text(body, "apiKey", true);
        if (baseUrl != null) {
            try {
                URI uri = URI.create(baseUrl);
                if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null) throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) {
                throw badRequest("API Base URL 必须是完整的 http/https 地址");
            }
        }
        Double temperature = number(body, "temperature");
        if (temperature != null && (temperature < 0 || temperature > 2)) {
            throw badRequest("Temperature 必须在 0 到 2 之间");
        }
        Boolean mock = null;
        if (body.containsKey("mock")) {
            if (!(body.get("mock") instanceof Boolean value)) throw badRequest("mock 必须是布尔值");
            mock = value;
        }
        modelFactory.apply(baseUrl, apiKey, model, temperature, mock,
                config -> settingService.putJson(SettingService.MODEL_CONFIG, config));
        log.info("[settings] 模型配置已更新:base-url={} model={} mock={}",
                modelFactory.getBaseUrl(), modelFactory.getModel(), modelFactory.getMock());
        return getModel();
    }

    // ---------- 团队治理 ----------

    @GetMapping("/team")
    public synchronized Map<String, Object> getTeam() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("leaseSeconds", teamProperties.getLeaseSeconds());
        out.put("deliveryMaxAttempts", teamProperties.getDeliveryMaxAttempts());
        out.put("wakeBatchSize", teamProperties.getWakeBatchSize());
        return out;
    }

    @PutMapping("/team")
    public synchronized Map<String, Object> updateTeam(@RequestBody Map<String, Object> body) {
        Integer lease = integer(body, "leaseSeconds", 30);
        Integer attempts = integer(body, "deliveryMaxAttempts", 1);
        Integer batch = integer(body, "wakeBatchSize", 1);
        Map<String, Object> persist = new LinkedHashMap<>();
        persist.put("leaseSeconds", lease == null ? teamProperties.getLeaseSeconds() : lease);
        persist.put("deliveryMaxAttempts", attempts == null ? teamProperties.getDeliveryMaxAttempts() : attempts);
        persist.put("wakeBatchSize", batch == null ? teamProperties.getWakeBatchSize() : batch);
        settingService.putJson(SettingService.TEAM_GOVERNANCE, persist);
        if (lease != null) teamProperties.setLeaseSeconds(lease);
        if (attempts != null) teamProperties.setDeliveryMaxAttempts(attempts);
        if (batch != null) teamProperties.setWakeBatchSize(batch);
        return getTeam();
    }

    @Override
    public void afterPropertiesSet() {
        // SmartInitializingSingleton 的 afterSingletonsInstantiated 负责恢复配置;治理参数同样恢复
        Map<String, Object> gov = settingService.getJson(SettingService.TEAM_GOVERNANCE);
        if (gov.get("leaseSeconds") instanceof Number n) {
            teamProperties.setLeaseSeconds(n.intValue());
        }
        if (gov.get("deliveryMaxAttempts") instanceof Number n) {
            teamProperties.setDeliveryMaxAttempts(n.intValue());
        }
        if (gov.get("wakeBatchSize") instanceof Number n) {
            teamProperties.setWakeBatchSize(n.intValue());
        }
    }

    // ---------- 系统状态 ----------

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pg", pingPg() ? "up" : "down");
        out.put("redis", pingRedis() ? "up" : "down");
        out.put("agents", List.copyOf(registry.all().keySet()));
        // 状态页要独立报告各组件:单个查询失败降级为 null,不能拖垮整个接口
        Object teams;
        try {
            teams = teamMapper.selectCountByQuery(QueryWrapper.create());
        } catch (Exception e) {
            log.warn("[settings] 团队数查询失败,状态降级", e);
            teams = null;
        }
        out.put("teams", teams);
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("baseUrl", modelFactory.getBaseUrl());
        model.put("model", modelFactory.getModel());
        model.put("mock", modelFactory.getMock());
        out.put("model", model);
        return out;
    }

    private boolean pingPg() {
        try (Connection c = dataSource.getConnection()) {
            return c.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean pingRedis() {
        try (org.springframework.data.redis.connection.RedisConnection connection =
                     redisTemplate.getConnectionFactory().getConnection()) {
            String pong = new String(connection.ping());
            return "PONG".equalsIgnoreCase(pong.trim());
        } catch (Exception e) {
            return false;
        }
    }

    @ExceptionHandler(ResponseStatusException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalidSettings(ResponseStatusException error) {
        return Map.of("message", error.getReason());
    }

    private String text(Map<String, Object> body, String name, boolean allowBlank) {
        if (!body.containsKey(name)) return null;
        if (!(body.get(name) instanceof String value) || (!allowBlank && value.isBlank())) {
            throw badRequest(name + " 必须是" + (allowBlank ? "文本" : "非空文本"));
        }
        return value.trim();
    }

    private Double number(Map<String, Object> body, String name) {
        if (!body.containsKey(name)) return null;
        Object raw = body.get(name);
        // 数字与数字字符串都接受(curl/表单可能传 "0.7"),其余报 400 而不是 500
        if (raw instanceof Number value && Double.isFinite(value.doubleValue())) {
            return value.doubleValue();
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                double parsed = Double.parseDouble(s.trim());
                if (Double.isFinite(parsed)) return parsed;
            } catch (NumberFormatException ignored) {
                // 落到 badRequest
            }
        }
        throw badRequest(name + " 必须是有效数字");
    }

    private Integer integer(Map<String, Object> body, String name, int minimum) {
        Double value = number(body, name);
        if (value == null) return null;
        if (value != Math.rint(value) || value < minimum || value > Integer.MAX_VALUE) {
            throw badRequest(name + " 必须是 " + minimum + " 到 " + Integer.MAX_VALUE + " 之间的整数");
        }
        return value.intValue();
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
