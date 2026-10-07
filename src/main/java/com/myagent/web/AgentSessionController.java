package com.myagent.web;

import com.myagent.engine.ChatStore;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 会话目录与历史(dsh 会话范式的服务端部分):
 * - GET  /{sn}            成员的会话目录(最近活跃在前,含标题/条数)
 * - GET  /history         某会话的历史消息(时间正序)
 * - DELETE /{threadId}    删除会话(仅消息记录;图检查点保留,不影响新会话)
 * - POST /fork            fork 会话(Codex「编辑上一条并分叉」):复制消息与图检查点
 * - GET  /search          跨会话消息搜索(Codex Ctrl+R 语义)
 */
@RestController
@RequestMapping("/api/agent/sessions")
@RequiredArgsConstructor
public class AgentSessionController {

    private final ChatStore chatStore;

    @GetMapping("/{sn}")
    public List<Map<String, Object>> sessions(@PathVariable String sn,
                                              @RequestParam(required = false) String project) {
        return chatStore.sessions(sn, project);
    }

    @GetMapping("/history")
    public List<Map<String, Object>> history(@RequestParam String sessionId,
                                             @RequestParam(defaultValue = "200") int limit) {
        return chatStore.history(sessionId, Math.min(Math.max(limit, 1), 500));
    }

    @DeleteMapping("/{threadId}")
    public Map<String, Object> delete(@PathVariable String threadId) {
        chatStore.deleteSession(threadId);
        return Map.of("deleted", threadId);
    }

    @Data
    public static class ForkRequest {
        private String sourceSessionId;
        private String newSessionId;
        private String agentSn;
        /** 保留源会话前 keep 条消息(被编辑/重试的消息及其之后不复制) */
        private int keep;
        private String title;
        private Long projectId;
    }

    @PostMapping("/fork")
    public Map<String, Object> fork(@RequestBody ForkRequest request) {
        if (request.getSourceSessionId() == null || request.getNewSessionId() == null
                || request.getAgentSn() == null) {
            throw new IllegalArgumentException("sourceSessionId/newSessionId/agentSn 必填");
        }
        if (chatStore.exists(request.getNewSessionId())) {
            throw new IllegalArgumentException("新会话 ID 已存在");
        }
        chatStore.forkSession(request.getSourceSessionId(), request.getNewSessionId(),
                request.getAgentSn(), Math.max(request.getKeep(), 0),
                request.getTitle(), request.getProjectId());
        return Map.of("sessionId", request.getNewSessionId(),
                "copied", Math.max(request.getKeep(), 0));
    }

    @GetMapping("/search")
    public List<Map<String, Object>> search(@RequestParam String q,
                                            @RequestParam(defaultValue = "30") int limit) {
        String query = q == null ? "" : q.trim();
        if (query.isEmpty()) {
            return List.of();
        }
        return chatStore.searchMessages(query, Math.min(Math.max(limit, 1), 50));
    }
}
