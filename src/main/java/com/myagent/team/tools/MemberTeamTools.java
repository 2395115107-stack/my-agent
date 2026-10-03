package com.myagent.team.tools;

import com.myagent.team.MailboxService;
import com.myagent.team.TaskBoardService;
import com.myagent.team.TeamToolSupport;
import com.myagent.team.entity.MailboxMessage;
import com.myagent.team.entity.Team;
import com.myagent.team.entity.TeamTask;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Map;

/**
 * 成员工具集(any 档位)。非 Spring 单例:每个成员 agent 构建时以自己的 sn 实例化,
 * 从而在工具层面绑定"我是谁"——权限边界 = 哪个工具集被注册到哪个 Agent。
 */
public class MemberTeamTools {

    private final String selfSn;
    private final TeamToolSupport support;
    private final TaskBoardService taskBoardService;
    private final MailboxService mailboxService;

    public MemberTeamTools(String selfSn, TeamToolSupport support,
                           TaskBoardService taskBoardService, MailboxService mailboxService) {
        this.selfSn = selfSn;
        this.support = support;
        this.taskBoardService = taskBoardService;
        this.mailboxService = mailboxService;
    }

    @Tool(description = "查看团队成员花名册。展示名只用于对话,所有工具参数一律使用 sn")
    public String teamMembers() {
        Team team = support.currentTeam();
        return support.json(Map.of("members", support.membersPayload(team.getId())));
    }

    @Tool(description = "读取自己的未读信箱(FIFO,最多 50 条)。消息在本回合成功结束后才会标记已读")
    public String teamReadMessages() {
        List<MailboxMessage> unread = mailboxService.unread(selfSn, 50);
        return support.json(Map.of(
                "count", unread.size(),
                "messages", unread.stream().map(m -> Map.of(
                        "id", m.getId(),
                        "type", m.getMsgType(),
                        "priority", m.getPriority(),
                        "content", m.getContent(),
                        "payload", m.getPayload() == null ? "" : m.getPayload()
                )).toList()));
    }

    @Tool(description = "发消息给指定成员(to=sn),to=* 表示全员广播。注意:分配任务用 team_task_create 即会自动唤醒,无需再发消息交接")
    public String teamSendMessage(
            @ToolParam(description = "目标成员 sn,或 * 广播") String toSn,
            @ToolParam(description = "消息内容") String content) {
        Team team = support.currentTeam();
        if ("*".equals(toSn)) {
            support.membersPayload(team.getId()).stream()
                    .map(m -> (String) m.get("sn"))
                    .filter(sn -> !sn.equals(selfSn))
                    .forEach(sn -> mailboxService.deliver(team.getId(), sn,
                            MailboxMessage.TYPE_MESSAGE, 0, content, Map.of("from", selfSn)));
            return "broadcast ok";
        }
        support.requireMember(team.getId(), toSn);
        mailboxService.deliver(team.getId(), toSn, MailboxMessage.TYPE_MESSAGE, 0, content, Map.of("from", selfSn));
        return "sent to " + toSn;
    }

    @Tool(description = "按 owner/status 过滤任务列表")
    public String teamTaskList(
            @ToolParam(required = false, description = "按成员 sn 过滤") String ownerSn,
            @ToolParam(required = false, description = "按状态过滤:PENDING/IN_PROGRESS/COMPLETED") String status) {
        Team team = support.currentTeam();
        List<TeamTask> tasks = taskBoardService.list(team.getId(), ownerSn, status);
        return support.json(Map.of("tasks", tasks.stream().map(t -> Map.of(
                "id", t.getId(), "subject", t.getSubject(), "owner", t.getOwnerSn() == null ? "" : t.getOwnerSn(),
                "status", t.getStatus(), "blockedBy", t.getBlockedBy() == null ? "[]" : t.getBlockedBy()
        )).toList()));
    }

    @Tool(description = "更新任务状态:IN_PROGRESS(开工)或 COMPLETED(完工,自动解锁依赖方)")
    public String teamTaskUpdate(
            @ToolParam(description = "任务 ID") Long taskId,
            @ToolParam(description = "新状态") String newStatus) {
        taskBoardService.update(taskId, newStatus, selfSn);
        return "task #" + taskId + " -> " + newStatus;
    }
}
