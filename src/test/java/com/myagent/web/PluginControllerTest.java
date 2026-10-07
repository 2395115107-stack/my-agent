package com.myagent.web;

import com.myagent.config.SettingService;
import com.myagent.plugin.AgentPlugin;
import com.myagent.plugin.AgentRebuildService;
import com.myagent.plugin.PluginConfigField;
import com.myagent.plugin.PluginService;
import com.myagent.plugin.PluginServiceTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 插件 API 契约:视图形状、启停触发热重建、未知 key 404 / 非法配置 400 的边界归属。 */
class PluginControllerTest {

    private final Map<String, String> stored = new HashMap<>();
    private PluginService service;
    private AgentRebuildService rebuild;
    private PluginController controller;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(String.class), any(Object[].class))).thenAnswer(call -> {
            Object[] args = (Object[]) call.getRawArguments()[2];
            String value = stored.get(args[0]);
            if (value == null) throw new EmptyResultDataAccessException(1);
            return value;
        });
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(call -> {
            Object[] args = (Object[]) call.getRawArguments()[1];
            stored.put((String) args[0], (String) args[1]);
            return 1;
        });
        service = new PluginService(new SettingService(jdbc), List.of(new DemoPlugin()));
        rebuild = mock(AgentRebuildService.class);
        controller = new PluginController(service, rebuild);
    }

    @Test
    void listReturnsManagerView() {
        List<Map<String, Object>> list = controller.list();
        assertEquals(1, list.size());
        Map<String, Object> view = list.get(0);
        assertEquals("demo", view.get("key"));
        assertEquals(Boolean.FALSE, view.get("enabled"));
        assertNotNull(view.get("configFields"));
        assertNotNull(view.get("tools"));
        assertFalse(((List<?>) view.get("tools")).isEmpty());
        verify(rebuild, never()).rebuildAll();
    }

    @Test
    void enableTakesEffectAndTriggersRebuild() {
        Map<String, Object> view = controller.enable("demo", Map.of("config", Map.of("retries", 4)));
        assertTrue((Boolean) view.get("enabled"));
        assertEquals(4.0, ((Number) ((Map<?, ?>) view.get("config")).get("retries")).doubleValue());
        verify(rebuild, times(1)).rebuildAll();
        assertEquals(1, service.toolBeans().size());
    }

    @Test
    void enableWithoutBodyDefaultsConfig() {
        Map<String, Object> view = controller.enable("demo", null);
        assertTrue((Boolean) view.get("enabled"));
        assertEquals(3.0, ((Number) ((Map<?, ?>) view.get("config")).get("retries")).doubleValue());
    }

    @Test
    void disableTriggersRebuild() {
        controller.enable("demo", null);
        Map<String, Object> view = controller.disable("demo");
        assertFalse((Boolean) view.get("enabled"));
        assertTrue(service.toolBeans().isEmpty());
        verify(rebuild, times(2)).rebuildAll();
    }

    @Test
    void unknownKeyThrowsForHttpMapping() {
        assertThrows(NoSuchElementException.class, () -> controller.enable("nope", null));
        Map<String, String> notFound = controller.unknownPlugin(new NoSuchElementException("插件不存在: nope"));
        assertTrue(notFound.get("message").contains("nope"));
    }

    @Test
    void invalidConfigThrowsForHttpMapping() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.enable("demo", Map.of("config", Map.of("retries", 0))));
        Map<String, String> badRequest =
                controller.invalidPluginRequest(new IllegalArgumentException("重试次数 必须在 1.0 到 10.0 之间"));
        assertTrue(badRequest.get("message").contains("1.0"));
    }

    @Test
    void inventoryIsReadOnlySnapshot() {
        Map<String, Object> inventory = controller.inventory();
        assertEquals("全局", inventory.get("scope"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) inventory.get("plugins");
        assertEquals("DISABLED", rows.get(0).get("status"));
        assertFalse(((List<?>) rows.get(0).get("tools")).isEmpty());
    }

    static class DemoPlugin implements AgentPlugin {
        @Override
        public String key() { return "demo"; }

        @Override
        public String title() { return "演示插件"; }

        @Override
        public String description() { return "API 契约测试用"; }

        @Override
        public List<PluginConfigField> configFields() {
            return List.of(PluginConfigField.number("retries", "重试次数", 3, 1, 10, ""));
        }

        @Override
        public List<Object> toolBeans(Map<String, Object> config) {
            return List.of(new PluginServiceTest.FakeToolBean());
        }
    }
}
