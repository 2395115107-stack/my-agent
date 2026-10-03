package com.myagent.web;

import com.myagent.engine.AgentRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 注册表目录(客户端挂成员时选择已有 Agent 用)。
 */
@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class AgentDirectoryController {

    private final AgentRegistry registry;

    @GetMapping
    public List<String> list() {
        return registry.all().keySet().stream().sorted().toList();
    }
}
