package com.myagent.team.tools;

import com.myagent.team.MailboxService;
import com.myagent.team.SpawnService;
import com.myagent.team.TaskBoardService;
import com.myagent.team.TeamScheduler;
import com.myagent.team.TeamToolSupport;
import com.myagent.team.entity.MailboxMessage;
import com.myagent.team.entity.Team;
import com.myagent.team.entity.TeamMember;
import com.myagent.team.entity.TeamTask;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Leader 工具集(含 lead_only 档位:spawn / interrupt / shutdown)。
 * 只注册给 Leader Agent —— 服务端强制权限,不靠提示词自觉。
 * actor = 当前团队 leadSn;MVP 单活跃团队假设见 TeamToolSupport。
 */
@Component
public class LeaderTeamTools {

    private final TeamToolSupport support;
    private final TaskBoardService taskBoardService;
    private final MailboxService mailboxService;
    private final SpawnService spawnService;
    private final TeamScheduler scheduler;
    private final com.myagent.team.mapper.TeamMemberMapper memberMapper;

    public LeaderTeamTools(TeamToolSupport support, TaskBoardService taskBoardService,
                           MailboxService mailboxService, SpawnService spawnService,
                           TeamScheduler scheduler, com.myagent.team.mapper.TeamMemberMapper memberMapper) {
        this.support = support;
        this.taskBoardService = taskBoardService;
        this.mailboxService = mailboxService;
        this.spawnService = spawnService;
        this.scheduler = scheduler;
        this.memberMapper = memberMapper;
    }

    @Tool(description = "查看团队成员花名册(sn、角色、状态、未读数)。所有工具参数一律使用 sn,展示名只用于对话")
    public String teamMembers() {
        Team team = support.currentTeam();
        return support.json(Map.of("leadSn", team.getLeadSn(), "members", support.membersPayload(team.getId())));
    }

    @Tool(description = "创建任务并分配给成员(owner=sn)。分配即自动唤醒该成员,无需再 teamSendMessage 交接。若任务依赖其他任务的产出,owner 必须等依赖任务 COMPLETED 后再被分配——先派前置任务,等其 idle 通知,再派后置任务")
    public String teamTaskCreate(
            @ToolParam(description = "任务标题") String subject,
            @ToolParam(description = "任务描述与验收标准") String description,
            @ToolParam(description = "负责成员 sn") String ownerSn,
            @ToolParam(required = false, description = "依赖任务 ID,逗号分隔,如 \"3,7\"") String blockedByCsv) {
        Team team = support.currentTeam();
        support.requireMember(team.getId(), ownerSn);
        List<Long> blockedBy = blockedByCsv == null || blockedByCsv.isBlank()
                ? List.of()
                : java.util.Arrays.stream(blockedByCsv.split(",")).map(String::trim)
                        .filter(s -> s.matches("\\d+")).map(Long::valueOf).toList();
        TeamTask task = taskBoardService.create(team.getId(), subject, description, ownerSn, blockedBy, team.getLeadSn());
        return support.json(Map.of("taskId", task.getId(), "status", task.getStatus(), "assignedTo", ownerSn));
    }

    @Tool(description = "更新任务状态(PENDING/IN_PROGRESS/COMPLETED/DELETED)")
    public String teamTaskUpdate(
            @ToolParam(description = "任务 ID") Long taskId,
            @ToolParam(description = "新状态") String newStatus) {
        Team team = support.currentTeam();
        taskBoardService.update(taskId, newStatus, team.getLeadSn());
        return "task #" + taskId + " -> " + newStatus;
    }

    @Tool(description = "按 owner/status 过滤任务列表")
    public String teamTaskList(
            @ToolParam(required = false, description = "按成员 sn 过滤") String ownerSn,
            @ToolParam(required = false, description = "按状态过滤") String status) {
        Team team = support.currentTeam();
        return support.json(Map.of("tasks", taskBoardService.list(team.getId(), ownerSn, status).stream()
                .map(t -> Map.of("id", t.getId(), "subject", t.getSubject(), "owner", t.getOwnerSn(),
                        "status", t.getStatus())).toList()));
    }

    @Tool(description = "读取 Leader 自己的未读信箱(成员完工汇报、idle 通知、投递异常通知)")
    public String teamReadMessages() {
        Team team = support.currentTeam();
        List<MailboxMessage> unread = mailboxService.unread(team.getLeadSn(), 50);
        return support.json(Map.of("count", unread.size(), "messages", unread.stream()
                .map(m -> Map.of("id", m.getId(), "type", m.getMsgType(), "content", m.getContent()))
                .toList()));
    }

    @Tool(description = "发消息给指定成员(to=sn)或全员广播(to=*)。仅用于补充上下文;分配/改派任务请用 teamTaskCreate")
    public String teamSendMessage(
            @ToolParam(description = "目标成员 sn 或 *") String toSn,
            @ToolParam(description = "消息内容") String content) {
        Team team = support.currentTeam();
        if ("*".equals(toSn)) {
            support.membersPayload(team.getId()).stream().map(m -> (String) m.get("sn"))
                    .forEach(sn -> mailboxService.deliver(team.getId(), sn,
                            MailboxMessage.TYPE_MESSAGE, 0, content, Map.of("from", team.getLeadSn())));
            return "broadcast ok";
        }
        support.requireMember(team.getId(), toSn);
        mailboxService.deliver(team.getId(), toSn, MailboxMessage.TYPE_MESSAGE, 0, content,
                Map.of("from", team.getLeadSn()));
        return "sent to " + toSn;
    }

    // ---------- lead_only 档位 ----------

    @Tool(description = "拉新成员入队(lead_only)。治理要求:先在回复里向用户文字提案(成员名/职责),获得确认后再调用")
    public String teamSpawnAgent(
            @ToolParam(description = "成员显示名") String displayName,
            @ToolParam(description = "成员职责描述(将并入其系统提示词)") String specialtyPrompt) {
        Team team = support.currentTeam();
        TeamMember member = spawnService.spawn(team.getId(), displayName, specialtyPrompt);
        return support.json(Map.of("sn", member.getAgentSn(), "displayName", displayName, "status", member.getStatus()));
    }

    @Tool(description = "中断成员当前回合,并把替换指令持久化为其信箱最高优先级消息,成员下回合必然最先读到(lead_only)")
    public String teamInterruptAgent(
            @ToolParam(description = "目标成员 sn") String agentSn,
            @ToolParam(description = "替换指令") String replacementInstruction) {
        Team team = support.currentTeam();
        support.requireMember(team.getId(), agentSn);
        scheduler.interrupt(team.getId(), agentSn, replacementInstruction);
        return "interrupted " + agentSn;
    }

    @Tool(description = "请求成员下线:成员槽位暂停并收到下线请求(lead_only)")
    public String teamShutdownAgent(
            @ToolParam(description = "目标成员 sn") String agentSn) {
        Team team = support.currentTeam();
        TeamMember member = support.requireMember(team.getId(), agentSn);
        member.setStatus(TeamMember.STATUS_PAUSED);
        memberMapper.update(member);
        mailboxService.deliver(team.getId(), agentSn, MailboxMessage.TYPE_SHUTDOWN_REQ, 5,
                "Leader 请求你下线", Map.of("from", team.getLeadSn()));
        return "shutdown requested: " + agentSn;
    }

    @Tool(description = "恢复已暂停成员的槽位并立即处理其信箱积压(lead_only)。用于误暂停后的补救或成员修复完成后的重新上岗")
    public String teamResumeAgent(
            @ToolParam(description = "目标成员 sn") String agentSn) {
        Team team = support.currentTeam();
        TeamMember member = support.requireMember(team.getId(), agentSn);
        member.setStatus(TeamMember.STATUS_ACTIVE);
        memberMapper.update(member);
        scheduler.kick(team.getId(), agentSn);
        return "resumed: " + agentSn;
    }
}
