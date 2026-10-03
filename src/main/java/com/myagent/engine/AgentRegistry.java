package com.myagent.engine;

import com.alibaba.cloud.ai.graph.agent.Agent;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 智能体注册表。对齐 Phoenix AgentStaticLoader.java:15-50。
 * 所有成员(含运行时 spawn 出来的)都注册在这里;TeamScheduler 按 sn 取用。
 */
@Component
public class AgentRegistry {

    private final Map<String, Agent> agents = new ConcurrentHashMap<>();

    public void register(String sn, Agent agent) {
        agents.put(sn, agent);
    }

    public Agent load(String sn) {
        Agent agent = agents.get(sn);
        if (agent == null) {
            throw new NoSuchElementException("Agent not found: " + sn);
        }
        return agent;
    }

    public boolean exists(String sn) {
        return agents.containsKey(sn);
    }

    public Map<String, Agent> all() {
        return Map.copyOf(agents);
    }
}
