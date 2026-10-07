package com.myagent.plugin;

import com.myagent.config.SettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 插件运行时回归:默认全关、启停/配置校验与持久化、恢复失败标「启动失败」。
 * 与 SettingsControllerTest 同一策略:只替换 SQL 边界,JSON 持久化与运行态用真实现。
 */
public class PluginServiceTest {

    private final Map<String, String> stored = new HashMap<>();
    private SettingService settings;
    private AgentPlugin configurable; // 带一个 number 字段 + 构建可失败
    private AgentPlugin plain;        // 无配置
    private PluginService service;

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
        settings = new SettingService(jdbc);
        configurable = new FakePlugin("fake-a", "伪插件A",
                List.of(PluginConfigField.number("retries", "重试次数", 3, 1, 10, "演示字段")),
                null); // zone=boom 时构建失败,这里不配
        plain = new FakePlugin("fake-b", "伪插件B", List.of(), null);
        service = new PluginService(settings, List.of(configurable, plain));
    }

    private void restore() {
        ReflectionTestUtils.invokeMethod(service, "restore");
    }

    @Test
    void freshStartDefaultsAllDisabledWithNoTools() {
        restore();
        assertFalse((Boolean) service.view("fake-a").get("enabled"));
        assertTrue(service.toolBeans().isEmpty());
        for (Map<String, Object> row : service.inventory()) {
            assertEquals("DISABLED", row.get("status"));
            assertEquals("全局", row.get("scope"));
        }
    }

    @Test
    void enableUsesDefaultsAndPersists() {
        restore();
        Map<String, Object> view = service.enable("fake-a", null);
        assertTrue((Boolean) view.get("enabled"));
        assertEquals(3.0, ((Number) ((Map<?, ?>) view.get("config")).get("retries")).doubleValue());
        assertEquals(1, service.toolBeans().size());
        assertTrue(stored.get(PluginService.SETTING_KEY).contains("fake-a"));
        assertEquals("ACTIVE", service.inventory().get(0).get("status"));
    }

    @Test
    void enableWithoutBodyReusesSavedConfig() {
        restore();
        service.updateConfig("fake-a", Map.of("retries", 7));
        service.disable("fake-a");
        Map<String, Object> view = service.enable("fake-a", null);
        assertEquals(7.0, ((Number) ((Map<?, ?>) view.get("config")).get("retries")).doubleValue());
    }

    @Test
    void unknownPluginKeyThrows() {
        restore();
        assertThrows(NoSuchElementException.class, () -> service.enable("nope", null));
        assertThrows(NoSuchElementException.class, () -> service.disable("nope"));
        assertThrows(NoSuchElementException.class, () -> service.updateConfig("nope", Map.of()));
    }

    @Test
    void invalidConfigRejectedAndStateUntouched() {
        restore();
        assertThrows(IllegalArgumentException.class,
                () -> service.enable("fake-a", Map.of("retries", 99)));
        assertThrows(IllegalArgumentException.class,
                () -> service.updateConfig("fake-a", Map.of("hacker", "x")));
        assertFalse((Boolean) service.view("fake-a").get("enabled"));
        assertTrue(service.toolBeans().isEmpty());
        assertFalse(stored.containsKey(PluginService.SETTING_KEY));
    }

    @Test
    void disableRemovesToolsButKeepsConfig() {
        restore();
        service.updateConfig("fake-a", Map.of("retries", 5));
        service.enable("fake-a", null);
        assertEquals(1, service.toolBeans().size());
        service.disable("fake-a");
        assertTrue(service.toolBeans().isEmpty());
        assertEquals(5.0, ((Number) ((Map<?, ?>) service.view("fake-a").get("config")).get("retries")).doubleValue());
        assertEquals("DISABLED", service.inventory().get(0).get("status"));
    }

    @Test
    void restoreMarksFailedWhenToolBuildThrows() {
        configurable = new FakePlugin("fake-a", "伪插件A",
                List.of(PluginConfigField.text("zone", "区域", "ok", "")),
                "boom"); // 配置为 boom 时 toolBeans 抛异常
        service = new PluginService(settings, List.of(configurable, plain));
        stored.put(PluginService.SETTING_KEY,
                "{\"fake-a\":{\"enabled\":true,\"config\":{\"zone\":\"boom\"}}}");
        restore();
        Map<String, Object> row = service.inventory().get(0);
        assertEquals("FAILED", row.get("status"));
        assertEquals("boom", row.get("error"));
        assertTrue(service.toolBeans().isEmpty());
    }

    @Test
    void restoreDropsInvalidPersistedConfigForDisabledPlugin() {
        stored.put(PluginService.SETTING_KEY,
                "{\"fake-a\":{\"enabled\":false,\"config\":{\"retries\":\"not-a-number\"}}}");
        restore();
        assertEquals(3.0, ((Number) ((Map<?, ?>) service.view("fake-a").get("config")).get("retries")).doubleValue());
        assertEquals("DISABLED", service.inventory().get(0).get("status"));
    }

    /** 可配置的假插件:toolValues 里的值命中 boomValue 时构建失败,模拟探针失败路径。 */
    private static final class FakePlugin implements AgentPlugin {
        private final String key;
        private final String title;
        private final List<PluginConfigField> fields;
        private final String boomValue;

        FakePlugin(String key, String title, List<PluginConfigField> fields, String boomValue) {
            this.key = key;
            this.title = title;
            this.fields = fields;
            this.boomValue = boomValue;
        }

        @Override
        public String key() { return key; }

        @Override
        public String title() { return title; }

        @Override
        public String description() { return "测试用假插件"; }

        @Override
        public List<PluginConfigField> configFields() { return fields; }

        @Override
        public List<Object> toolBeans(Map<String, Object> config) {
            if (boomValue != null && boomValue.equals(config.get("zone"))) {
                throw new IllegalStateException("boom");
            }
            return List.of(new FakeToolBean());
        }
    }

    public static class FakeToolBean {
        @org.springframework.ai.tool.annotation.Tool(description = "fake tool")
        public String fakeTool() {
            return "ok";
        }
    }
}
