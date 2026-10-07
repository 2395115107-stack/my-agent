package com.myagent.config;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 设置持久化(tbl_setting):模型配置、团队治理参数。设置页在线修改,重启保留。
 */
@Service
@RequiredArgsConstructor
public class SettingService {

    public static final String MODEL_CONFIG = "model.config";
    public static final String TEAM_GOVERNANCE = "team.governance";
    public static final String PATROL_CONFIG = "patrol.config";

    private final JdbcTemplate jdbc;

    public String get(String key) {
        try {
            return jdbc.queryForObject("SELECT sval FROM tbl_setting WHERE skey = ?", String.class, key);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getJson(String key) {
        String raw = get(key);
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(raw, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    public void putJson(String key, Map<String, Object> value) {
        try {
            String raw = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
            jdbc.update("INSERT INTO tbl_setting(skey, sval, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP) " +
                            "ON CONFLICT (skey) DO UPDATE SET sval = EXCLUDED.sval, updated_at = CURRENT_TIMESTAMP",
                    key, raw);
        } catch (Exception e) {
            throw new IllegalStateException("设置保存失败", e);
        }
    }
}
