package com.myagent.engine;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话消息持久化(dsh 式服务端历史):直聊与团队回合统一落 tbl_chat_message,
 * 会话登记表 tbl_session 承载目录/标题/项目归属。threadId = 图检查点 threadId,一一对应。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatStore {

    private static final int MAX_CONTENT = 16000;
    /* RedisSaver 的 key 布局(graph-core 2.0.0-M1.1 RedisSaver):meta hash 的 thread_id 字段
       指向线程内部 id,content:{内部id} 存整条检查点链(默认 codec 序列化)。fork 按字节复制。 */
    private static final String META_PREFIX = "graph:thread:meta:";
    private static final String REVERSE_PREFIX = "graph:thread:reverse:";
    private static final String CONTENT_PREFIX = "graph:checkpoint:content:";

    private final JdbcTemplate jdbc;
    private final RedissonClient redisson;

    public void append(String agentSn, String threadId, String role, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        String safe = content.length() > MAX_CONTENT ? content.substring(0, MAX_CONTENT) : content;
        jdbc.update("INSERT INTO tbl_chat_message(agent_sn, thread_id, role, content, created_at) VALUES (?,?,?,?,now())",
                agentSn, threadId, role, safe);
    }

    /** 会话登记:首条用户消息时创建(标题取消息开头);重复调用幂等。 */
    public void ensureSession(String agentSn, String threadId, String title, Long projectId) {
        String safe = title == null ? "(无文本回合)" : title.replace('\n', ' ');
        if (safe.length() > 120) {
            safe = safe.substring(0, 120);
        }
        jdbc.update("INSERT INTO tbl_session(thread_id, agent_sn, project_id, title, created_at) VALUES (?,?,?,?,now()) " +
                        "ON CONFLICT (thread_id) DO NOTHING",
                threadId, agentSn, projectId, safe);
    }

    /**
     * 会话目录。projectFilter:null = 全部;"none" = 普通对话(不归项目);
     * 数字字符串 = 指定项目。历史遗留会话(无登记行)先自动导入。
     */
    public List<Map<String, Object>> sessions(String agentSn, String projectFilter) {
        jdbc.update(
                "INSERT INTO tbl_session(thread_id, agent_sn, title, created_at) " +
                        "SELECT t.thread_id, t.agent_sn, left(t.first_content, 120), t.first_at FROM ( " +
                        "  SELECT thread_id, agent_sn, " +
                        "    (array_agg(content ORDER BY id))[1] AS first_content, " +
                        "    (array_agg(created_at ORDER BY id))[1] AS first_at " +
                        "  FROM tbl_chat_message WHERE role = 'user' GROUP BY thread_id, agent_sn " +
                        ") t ON CONFLICT (thread_id) DO NOTHING");

        String filter = "";
        Object[] args;
        if ("none".equals(projectFilter)) {
            filter = " AND s.project_id IS NULL";
            args = new Object[]{agentSn};
        } else if (projectFilter != null && projectFilter.matches("\\d+")) {
            filter = " AND s.project_id = ?";
            args = new Object[]{agentSn, Long.parseLong(projectFilter)};
        } else {
            args = new Object[]{agentSn};
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.thread_id, s.project_id, s.title, COUNT(m.id) AS msgs, MAX(m.created_at) AS last_at " +
                        "FROM tbl_session s LEFT JOIN tbl_chat_message m ON m.thread_id = s.thread_id " +
                        "WHERE s.agent_sn = ?" + filter + " " +
                        "GROUP BY s.thread_id, s.project_id, s.title ORDER BY MAX(m.created_at) DESC NULLS LAST",
                args);
        for (Map<String, Object> row : rows) {
            String title = row.get("title") == null ? "(无文本回合)" : String.valueOf(row.get("title"));
            if (title.startsWith("【团队唤醒】")) {
                title = "团队回合:" + title.replace("【团队唤醒】", "");
            }
            row.put("title", title);
        }
        return rows;
    }

    /** 历史消息(时间正序,最多 limit 条)。 */
    public List<Map<String, Object>> history(String threadId, int limit) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT role, content, created_at FROM tbl_chat_message WHERE thread_id = ? ORDER BY id DESC LIMIT ?",
                threadId, limit);
        java.util.Collections.reverse(rows);
        return rows;
    }

    public void deleteSession(String threadId) {
        jdbc.update("DELETE FROM tbl_chat_message WHERE thread_id = ?", threadId);
        jdbc.update("DELETE FROM tbl_session WHERE thread_id = ?", threadId);
    }

    public boolean exists(String threadId) {
        try {
            jdbc.queryForObject("SELECT 1 FROM tbl_chat_message WHERE thread_id = ? LIMIT 1",
                    Integer.class, threadId);
            return true;
        } catch (EmptyResultDataAccessException e) {
            return false;
        }
    }

    /**
     * fork 会话(Codex Esc×2「编辑上一条并从此分叉」的服务端部分):
     * 复制源会话前 keep 条消息到新 threadId 并登记(项目归属跟随源会话),
     * 同时按字节复制图检查点,让 fork 出的会话带着既有上下文继续对话。
     * 返回新 threadId。keep <= 0 或源无消息时只建登记,上下文从零开始。
     */
    public String forkSession(String sourceThreadId, String newThreadId, String agentSn,
                              int keep, String title, Long projectId) {
        List<Map<String, Object>> rows = keep > 0
                ? jdbc.queryForList("SELECT role, content FROM tbl_chat_message WHERE thread_id = ? ORDER BY id LIMIT ?",
                        sourceThreadId, keep)
                : List.of();
        for (Map<String, Object> row : rows) {
            jdbc.update("INSERT INTO tbl_chat_message(agent_sn, thread_id, role, content, created_at) VALUES (?,?,?,?,now())",
                    agentSn, newThreadId, row.get("role"), row.get("content"));
        }
        if (projectId == null) {
            try {
                projectId = jdbc.queryForObject(
                        "SELECT project_id FROM tbl_session WHERE thread_id = ?", Long.class, sourceThreadId);
            } catch (EmptyResultDataAccessException e) {
                // 源会话未登记(历史遗留):项目归属留空
            }
        }
        jdbc.update("INSERT INTO tbl_session(thread_id, agent_sn, project_id, title, created_at) VALUES (?,?,?,?,now()) " +
                        "ON CONFLICT (thread_id) DO NOTHING",
                newThreadId, agentSn, projectId, title);
        copyCheckpoint(sourceThreadId, newThreadId);
        return newThreadId;
    }

    /** 复制源 thread 的检查点链到新 thread:content 按字节复制到新的内部 id,meta/reverse 各自登记。 */
    private void copyCheckpoint(String sourceThreadId, String newThreadId) {
        try {
            Object internalId = redisson.getMap(META_PREFIX + sourceThreadId).get("thread_id");
            if (internalId == null) {
                return; // 源会话还没有成功回合,无检查点可复制
            }
            Object payload = redisson.getBucket(CONTENT_PREFIX + internalId).get();
            if (payload == null) {
                return;
            }
            String newInternalId = UUID.randomUUID().toString();
            redisson.getBucket(CONTENT_PREFIX + newInternalId).set(payload);
            var meta = redisson.getMap(META_PREFIX + newThreadId);
            meta.put("thread_id", newInternalId);
            // 与 RedisSaver 写入类型一致:is_released 是字符串 "true"/"false",不是布尔
            meta.put("is_released", "false");
            var reverse = redisson.getMap(REVERSE_PREFIX + newInternalId);
            reverse.put("thread_name", newThreadId);
            reverse.put("is_released", "false");
        } catch (Exception e) {
            // 检查点复制失败不阻断 fork:消息历史已复制,只是新会话短期记忆为空
            log.warn("fork 检查点复制失败 source={} new={}: {}", sourceThreadId, newThreadId, e.getMessage());
        }
    }

    /** 历史搜索(Codex Ctrl+R / dsh 会话搜索):消息内容匹配,返回所属会话与片段,最近在前。 */
    public List<Map<String, Object>> searchMessages(String query, int limit) {
        String like = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.queryForList(
                "SELECT m.thread_id, m.agent_sn, s.title, m.role, m.content, m.created_at " +
                        "FROM tbl_chat_message m LEFT JOIN tbl_session s ON s.thread_id = m.thread_id " +
                        "WHERE m.content ILIKE ? ESCAPE '\\' " +
                        "ORDER BY m.id DESC LIMIT ?",
                like, limit);
    }
}
