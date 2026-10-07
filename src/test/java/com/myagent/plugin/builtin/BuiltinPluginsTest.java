package com.myagent.plugin.builtin;

import com.myagent.plugin.PluginConfigField;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 内置插件工具逻辑回归:计算器求值、时间插件时区校验、网页抓取 SSRF 门禁。 */
class BuiltinPluginsTest {

    private static Object invokeTool(Object bean, String method, Class<?>[] paramTypes, Object... args)
            throws Exception {
        Method m = bean.getClass().getMethod(method, paramTypes);
        return m.invoke(bean, args);
    }

    // ---------- 计算器 ----------

    @Test
    void calculatorEvaluatesArithmetic() throws Exception {
        CalculatorPlugin plugin = new CalculatorPlugin();
        Object bean = plugin.toolBeans(Map.of()).get(0);
        String result = (String) invokeTool(bean, "calculate", new Class<?>[]{String.class}, "(3.5+1.5)*2");
        assertTrue(result.endsWith("= 10.0"), result);
        String precedence = (String) invokeTool(bean, "calculate", new Class<?>[]{String.class}, "2+3*4");
        assertTrue(precedence.endsWith("= 14.0"), precedence);
    }

    @Test
    void calculatorReportsErrorsAsText() throws Exception {
        CalculatorPlugin plugin = new CalculatorPlugin();
        Object bean = plugin.toolBeans(Map.of()).get(0);
        String divZero = (String) invokeTool(bean, "calculate", new Class<?>[]{String.class}, "1/0");
        assertTrue(divZero.startsWith("错误:") && divZero.contains("除以零"), divZero);
        String garbage = (String) invokeTool(bean, "calculate", new Class<?>[]{String.class}, "1+(2");
        assertTrue(garbage.startsWith("错误:"), garbage);
        String trailing = (String) invokeTool(bean, "calculate", new Class<?>[]{String.class}, "1 2");
        assertTrue(trailing.startsWith("错误:"), trailing);
    }

    // ---------- 时间与时区 ----------

    @Test
    void datetimeRejectsInvalidZone() {
        DateTimePlugin plugin = new DateTimePlugin();
        assertThrows(IllegalArgumentException.class, () -> plugin.toolBeans(Map.of("timezone", "Mars/Olympus")));
        assertEquals(1, plugin.toolBeans(Map.of("timezone", "UTC")).size());
    }

    @Test
    void datetimeReturnsNowWithZone() throws Exception {
        DateTimePlugin plugin = new DateTimePlugin();
        Object bean = plugin.toolBeans(Map.of("timezone", "Asia/Shanghai")).get(0);
        String now = (String) invokeTool(bean, "currentDatetime", new Class<?>[]{});
        assertTrue(now.contains("timezone=Asia/Shanghai"), now);
        assertTrue(now.contains("weekday="), now);
    }

    // ---------- 网页抓取 ----------

    @Test
    void webFetchBlocksLoopbackAndInternalHosts() throws Exception {
        WebFetchPlugin plugin = new WebFetchPlugin();
        Object bean = plugin.toolBeans(Map.of("timeoutSeconds", 5, "maxChars", 2000)).get(0);
        String loopback = (String) invokeTool(bean, "fetchUrl", new Class<?>[]{String.class},
                "http://127.0.0.1:8070/api/plugins");
        assertTrue(loopback.startsWith("错误:") && loopback.contains("内网"), loopback);
        String scheme = (String) invokeTool(bean, "fetchUrl", new Class<?>[]{String.class}, "ftp://example.com/x");
        assertTrue(scheme.startsWith("错误:") && scheme.contains("http"), scheme);
    }

    @Test
    void webFetchIsExperimentalWithConfigSchema() {
        WebFetchPlugin plugin = new WebFetchPlugin();
        assertTrue(plugin.experimental());
        assertEquals(2, plugin.configFields().size());
        PluginConfigField timeout = plugin.configFields().get(0);
        assertEquals("timeoutSeconds", timeout.name());
        assertEquals(1.0, timeout.min());
        assertEquals(60.0, timeout.max());
    }
}
