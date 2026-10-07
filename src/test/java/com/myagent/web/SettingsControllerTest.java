package com.myagent.web;

import com.myagent.config.ModelFactory;
import com.myagent.config.SettingService;
import com.myagent.engine.AgentRegistry;
import com.myagent.team.TeamProperties;
import com.myagent.team.mapper.TeamMapper;
import com.myagent.team.patrol.PatrolProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;
import java.sql.SQLException;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SettingsControllerTest {
    private final Map<String, String> stored = new HashMap<>();
    private boolean writeFails;
    private ModelFactory model;
    private TeamProperties team;
    private PatrolProperties patrol;
    private SettingService settings;
    private SettingsController controller;

    @BeforeEach
    void setUp() {
        // Only the external SQL boundary is replaced; JSON persistence and runtime state are real.
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(String.class), any(Object[].class))).thenAnswer(call -> {
            Object[] args = (Object[]) call.getRawArguments()[2];
            String value = stored.get(args[0]);
            if (value == null) throw new EmptyResultDataAccessException(1);
            return value;
        });
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(call -> {
            if (writeFails) throw new IllegalStateException("database unavailable");
            Object[] args = (Object[]) call.getRawArguments()[1];
            stored.put((String) args[0], (String) args[1]);
            return 1;
        });
        settings = new SettingService(jdbc);
        model = new ModelFactory();
        model.setBaseUrl("https://api.deepseek.com");
        model.setApiKey("sk-demo");
        model.setModel("deepseek-chat");
        model.setMock(true);
        ReflectionTestUtils.invokeMethod(model, "init");
        team = new TeamProperties();
        patrol = new PatrolProperties();
        controller = new SettingsController(model, settings, team, patrol, null, null, null, null);
    }

    @Test
    void invalidGovernanceDoesNotPartiallyApplyEarlierFields() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.updateTeam(Map.of("leaseSeconds", 900, "wakeBatchSize", 0)));
        assertEquals(400, error.getStatusCode().value());
        assertEquals(600, controller.getTeam().get("leaseSeconds"));
        assertTrue(settings.getJson(SettingService.TEAM_GOVERNANCE).isEmpty());
    }

    @Test
    void fractionalGovernanceIsRejectedInsteadOfTruncated() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.updateTeam(Map.of("deliveryMaxAttempts", 1.5)));
        assertEquals(400, error.getStatusCode().value());
        assertEquals(3, controller.getTeam().get("deliveryMaxAttempts"));
    }

    @Test
    void malformedModelTemperatureReturnsBadRequest() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.updateModel(Map.of("temperature", "bad")));
        assertEquals(400, error.getStatusCode().value());
        assertEquals(0.7, controller.getModel().get("temperature"));
        assertTrue(settings.getJson(SettingService.MODEL_CONFIG).isEmpty());
    }

    @Test
    void zeroTemperatureAndBlankKeyArePersistedWithoutLosingExistingKey() {
        model.setApiKey("existing-test-key");
        Map<String, Object> result = controller.updateModel(Map.of("temperature", 0, "apiKey", ""));
        assertEquals(0.0, result.get("temperature"));
        assertFalse(result.containsKey("apiKey"));
        Map<String, Object> saved = settings.getJson(SettingService.MODEL_CONFIG);
        assertEquals(0.0, saved.get("temperature"));
        assertEquals("existing-test-key", saved.get("apiKey"));
    }

    @Test
    void failedModelSaveKeepsTheActiveConfiguration() {
        writeFails = true;
        assertThrows(IllegalStateException.class,
                () -> controller.updateModel(Map.of("model", "unsaved-model", "temperature", 0)));
        assertEquals("deepseek-chat", controller.getModel().get("model"));
        assertEquals(0.7, controller.getModel().get("temperature"));
    }

    @Test
    void failedGovernanceSaveKeepsTheActiveConfiguration() {
        writeFails = true;
        assertThrows(IllegalStateException.class,
                () -> controller.updateTeam(Map.of("leaseSeconds", 900, "deliveryMaxAttempts", 4)));
        assertEquals(600, controller.getTeam().get("leaseSeconds"));
        assertEquals(3, controller.getTeam().get("deliveryMaxAttempts"));
    }

    @Test
    void statusStillReportsRedisWhenPostgresIsUnavailable() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("offline"));
        TeamMapper mapper = mock(TeamMapper.class);
        when(mapper.selectCountByQuery(any())).thenThrow(new IllegalStateException("offline"));
        RedisConnection connection = mock(RedisConnection.class);
        when(connection.ping()).thenReturn("PONG");
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenReturn(connection);
        StringRedisTemplate redis = new StringRedisTemplate();
        redis.setConnectionFactory(factory);
        controller = new SettingsController(model, settings, team, patrol, new AgentRegistry(), mapper, dataSource, redis);
        Map<String, Object> status = controller.status();
        assertEquals("down", status.get("pg"));
        assertEquals("up", status.get("redis"));
        assertNull(status.get("teams"));
        verify(connection).close();
    }

    // ---------- 巡查设置(热调 + 持久化) ----------

    @Test
    void patrolSettingsHotApplyAndPersist() {
        Map<String, Object> result = controller.updatePatrol(Map.of(
                "stalledMinutes", 20, "autoNudge", false, "notifyCooldownSeconds", 30));
        assertEquals(20, result.get("stalledMinutes"));
        assertEquals(false, result.get("autoNudge"));
        assertEquals(30, result.get("notifyCooldownSeconds"));
        assertEquals(20, patrol.getStalledMinutes());
        assertFalse(patrol.isAutoNudge());
        Map<String, Object> saved = settings.getJson(SettingService.PATROL_CONFIG);
        assertEquals(20, saved.get("stalledMinutes"));
        assertEquals(false, saved.get("autoNudge"));
        assertEquals(30, saved.get("notifyCooldownSeconds"));
    }

    @Test
    void invalidPatrolFieldRejectsWholeBatchWithoutTouchingRuntime() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.updatePatrol(Map.of("stalledMinutes", 15, "backlogUnread", 0)));
        assertEquals(400, error.getStatusCode().value());
        assertEquals(10, patrol.getStalledMinutes());
        assertTrue(settings.getJson(SettingService.PATROL_CONFIG).isEmpty());
    }

    @Test
    void probeCapBelowProbeBaseIsRejected() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.updatePatrol(Map.of("probeBaseSeconds", 300, "probeCapSeconds", 60)));
        assertEquals(400, error.getStatusCode().value());
        assertEquals(120, patrol.getProbeBaseSeconds());
    }

    @Test
    void booleanPatrolFieldsAcceptStringForm() {
        Map<String, Object> result = controller.updatePatrol(Map.of("enabled", "false"));
        assertEquals(false, result.get("enabled"));
        assertFalse(patrol.isEnabled());
    }

    @Test
    void retryBackoffFieldsPersistUnderGovernance() {
        Map<String, Object> result = controller.updateTeam(Map.of(
                "retryBackoffBaseSeconds", 10, "retryBackoffCapSeconds", 300));
        assertEquals(10, result.get("retryBackoffBaseSeconds"));
        assertEquals(300, result.get("retryBackoffCapSeconds"));
        assertEquals(300, controller.getTeam().get("retryBackoffCapSeconds"));
        Map<String, Object> saved = settings.getJson(SettingService.TEAM_GOVERNANCE);
        assertEquals(10, saved.get("retryBackoffBaseSeconds"));
    }
}
