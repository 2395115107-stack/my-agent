package com.myagent.web;

import com.myagent.team.MailboxService;
import com.myagent.team.SpawnService;
import com.myagent.team.TaskBoardService;
import com.myagent.team.entity.MailboxMessage;
import com.myagent.team.entity.Team;
import com.myagent.team.entity.TeamMember;
import com.myagent.team.entity.TeamTask;
import com.myagent.team.mapper.TeamMapper;
import com.myagent.team.mapper.TeamMemberMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 团队管理(引导/观测用):
 * - 建团时自动把 Leader(team-lead)写入花名册,Leader Agent 已在启动时注册进 AgentRegistry;
 * - 用户把目标指令投进 Leader 信箱 -> 调度器唤醒 Leader -> Leader 分配任务(分配即唤醒成员)。
 */
@RestController
@RequestMapping("/api/team")
@RequiredArgsConstructor
public class TeamController {

    private final TeamMapper teamMapper;
    private final TeamMemberMapper memberMapper;
    private final TaskBoardService taskBoardService;
    private final MailboxService mailboxService;
    private final SpawnService spawnService;
    private final com.myagent.team.TeamToolSupport support;
    private final com.myagent.team.TeamScheduler scheduler;
    private final com.myagent.team.TeamEventPublisher events;

    /** 团队列表(客户端团队切换器用)。 */
    @GetMapping
    public List<Team> list() {
        return teamMapper.selectAll();
    }

    @PostMapping
    public Team create(@RequestBody Map<String, String> body) {
        Team team = new Team();
        team.setName(body.getOrDefault("name", "demo-team"));
        team.setLeadSn("team-lead");
        team.setStatus("ACTIVE");
        team.setCreatedAt(LocalDateTime.now());
        teamMapper.insert(team);

        TeamMember lead = new TeamMember();
        lead.setTeamId(team.getId());
        lead.setAgentSn(team.getLeadSn());
        lead.setRole(TeamMember.ROLE_LEAD);
        lead.setDisplayName("Leader");
        lead.setStatus(TeamMember.STATUS_ACTIVE);
        lead.setCreatedAt(LocalDateTime.now());
        memberMapper.insert(lead);
        return team;
    }

    /** 手动加入成员:agentSn 为空 = 走 spawn(动态构建);否则把已有注册 Agent 挂进团队(重复挂载幂等返回已有成员)。 */
    @PostMapping("/{teamId}/members")
    public TeamMember addMember(@PathVariable Long teamId, @RequestBody Map<String, String> body) {
        String agentSn = body.get("agentSn");
        if (agentSn == null || agentSn.isBlank()) {
            return spawnService.spawn(teamId, body.getOrDefault("displayName", "member"),
                    body.getOrDefault("systemPrompt", "你是团队成员,服从 Leader 的任务分配。"));
        }
        var dup = memberMapper.selectListByQuery(
                com.mybatisflex.core.query.QueryWrapper.create()
                        .where(com.myagent.team.entity.table.TeamMemberTableDef.TEAM_MEMBER.TEAM_ID.eq(teamId))
                        .and(com.myagent.team.entity.table.TeamMemberTableDef.TEAM_MEMBER.AGENT_SN.eq(agentSn))
                        .limit(1));
        if (!dup.isEmpty()) {
            return dup.get(0);
        }
        TeamMember member = new TeamMember();
        member.setTeamId(teamId);
        member.setAgentSn(agentSn);
        member.setRole(TeamMember.ROLE_MEMBER);
        member.setDisplayName(body.getOrDefault("displayName", agentSn));
        member.setStatus(TeamMember.STATUS_ACTIVE);
        member.setCreatedAt(LocalDateTime.now());
        memberMapper.insert(member);
        return member;
    }

    /** 用户入口:把目标/补充上下文投进某个成员(通常是 Leader)的信箱,即唤醒。 */
    @PostMapping("/{teamId}/messages")
    public MailboxMessage postMessage(@PathVariable Long teamId, @RequestBody Map<String, String> body) {
        return mailboxService.deliver(teamId, body.get("toSn"),
                MailboxMessage.TYPE_MESSAGE, 0, body.get("content"), Map.of("from", "user"));
    }

    @GetMapping("/{teamId}/tasks")
    public List<TeamTask> tasks(@PathVariable Long teamId,
                                @RequestParam(required = false) String ownerSn,
                                @RequestParam(required = false) String status) {
        return taskBoardService.list(teamId, ownerSn, status);
    }

    /** 花名册(带未读数,客户端成员列表徽标用)。 */
    @GetMapping("/{teamId}/members")
    public List<Map<String, Object>> members(@PathVariable Long teamId) {
        return support.membersPayload(teamId);
    }

    /** 恢复已暂停成员的槽位,并立即对账处理其信箱积压。人工恢复 = 熔断计数清零(区别于自动探活)。 */
    @PostMapping("/{teamId}/members/{agentSn}/resume")
    public Map<String, Object> resume(@PathVariable Long teamId, @PathVariable String agentSn) {
        TeamMember member = support.requireMember(teamId, agentSn);
        member.setStatus(TeamMember.STATUS_ACTIVE);
        member.setPauseCount(0);
        member.setPausedAt(null);
        // ignoreNulls=false:paused_at 必须真正写回 NULL,否则残留旧值
        memberMapper.update(member, false);
        events.publish("agent_status_changed", teamId, agentSn + ":ACTIVE");
        scheduler.kick(teamId, agentSn);
        return Map.of("sn", agentSn, "status", TeamMember.STATUS_ACTIVE);
    }
}
