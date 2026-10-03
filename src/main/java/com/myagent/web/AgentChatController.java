package com.myagent.web;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.myagent.engine.AgentChatService;
import com.myagent.engine.AgentProfile;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对话入口(SSE)。帧协议对齐 Phoenix ReactAgentController.java:42-68:{content, end}。
 * sn 可传任意注册表中的 agent(Leader 或成员),便于前台直接与某个智能体对话。
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentChatController {

    private final AgentChatService chatService;

    @Data
    public static class ChatRequest {
        private String sn;
        private String sessionId;
        private String message;
        private String userId;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<Map<String, Object>> chat(@RequestBody ChatRequest request) {
        AgentProfile profile = AgentProfile.builder()
                .userId(request.getUserId() == null ? "anonymous" : request.getUserId())
                .sessionId(request.getSessionId() == null ? "web-" + System.currentTimeMillis() : request.getSessionId())
                .build();
        return chatService.stream(request.getSn(), request.getMessage(), profile)
                .map(output -> {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("content", "");
                    event.put("end", false);
                    if (output instanceof StreamingOutput<?> streaming
                            && streaming.chunk() != null && !output.isEND()) {
                        event.put("content", streaming.chunk());
                    }
                    if (output.isEND()) {
                        event.put("end", true);
                    }
                    return event;
                });
    }
}
