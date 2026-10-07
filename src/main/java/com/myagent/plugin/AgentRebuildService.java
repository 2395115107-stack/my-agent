package com.myagent.plugin;

import com.myagent.engine.AbstractTeamAgent;
import com.myagent.team.SpawnService;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 插件启停/配置变更后的 Agent 热重建:静态 Agent bean 各自 rebuildAndRegister,
 * 动态成员经 SpawnService 按花名册重建(跳过静态 sn,避免用成员工具集覆盖 Leader)。
 * 进行中的回合持有旧 Agent 引用继续跑完,新回合使用新工具集 —— 与模型热切换同语义。
 * 由 PluginController 在 PluginService 状态变更成功后调用。
 */
@Component
public class AgentRebuildService {

    private final List<AbstractTeamAgent> staticAgents;
    private final SpawnService spawnService;

    public AgentRebuildService(List<AbstractTeamAgent> staticAgents, SpawnService spawnService) {
        this.staticAgents = List.copyOf(staticAgents);
        this.spawnService = spawnService;
    }

    public synchronized void rebuildAll() {
        Set<String> staticSns = new HashSet<>();
        for (AbstractTeamAgent agent : staticAgents) {
            agent.rebuildAndRegister();
            staticSns.add(agent.sn());
        }
        spawnService.rebuildAllSpawned(staticSns);
    }
}
