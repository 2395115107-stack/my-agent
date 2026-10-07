package com.myagent.plugin.builtin;

import com.myagent.plugin.AgentPlugin;
import com.myagent.plugin.PluginConfigField;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 内置插件「时间与时区」:给调度/记录类任务提供时间感(模型自身不知道当前时间)。
 */
@Component
public class DateTimePlugin implements AgentPlugin {

    static final String DEFAULT_ZONE = "Asia/Shanghai";

    @Override
    public String key() {
        return "plugin-datetime";
    }

    @Override
    public String title() {
        return "时间与时区";
    }

    @Override
    public String description() {
        return "当前日期时间与时区查询。需要知道「现在几点/今天日期/星期几」的任务先开启本插件。";
    }

    @Override
    public List<PluginConfigField> configFields() {
        return List.of(PluginConfigField.text("timezone", "时区", DEFAULT_ZONE,
                "IANA 时区名,如 Asia/Shanghai、UTC"));
    }

    @Override
    public List<Object> toolBeans(Map<String, Object> config) {
        String zoneName = String.valueOf(config.getOrDefault("timezone", DEFAULT_ZONE));
        ZoneId zone;
        try {
            zone = ZoneId.of(zoneName);
        } catch (Exception e) {
            throw new IllegalArgumentException("时区无效: " + zoneName + "(需 IANA 名称,如 Asia/Shanghai)");
        }
        return List.of(new Tools(zone));
    }

    record Tools(ZoneId zone) {

        @Tool(description = "获取当前日期时间:ISO-8601 完整时间、时区与星期。回答“现在/今天/截止前还剩多久”类问题前先调用")
        public String currentDatetime() {
            ZonedDateTime now = ZonedDateTime.now(zone);
            return "now=" + now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    + ",date=" + now.toLocalDate()
                    + ",weekday=" + now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINESE)
                    + ",timezone=" + zone.getId();
        }
    }
}
