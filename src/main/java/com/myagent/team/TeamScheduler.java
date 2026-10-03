package com.myagent.team;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.myagent.engine.AgentChatService;
import com.myagent.engine.AgentProfile;
import com.myagent.engine.AgentRegistry;
import com.myagent.team.entity.MailboxMessage;
import com.myagent.team.entity.Team;
import com.myagent.team.entity.TeamMember;
import com.myagent.team.entity.TeamTurn;
import com.myagent.team.entity.table.TeamMemberTableDef;
import com.myagent.team.mapper.TeamMapper;
import com.myagent.team.mapper.TeamMemberMapper;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 调度器:谁来执行、何时唤醒。AionUi 三件套之三(scheduler/member_runtime)的服务端化:
 *
 * - 桌面版的"懒唤醒进程"不搬:服务端成员是注册表里的无状态对象,按需调用即可;
 * - 保留协议纪律:【等待 = 结束 turn,唤醒 = 信箱有新消息】;每成员同一时刻最多一个 turn;
 * - 双通道调度:信箱写入事件(主路径,@EventListener)+ 定时对账(兜底,@Scheduled);
 * - lease:turn 带租约,过期视为僵死 -> 失败回收,消息保留未读重投,超限暂停槽位。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamScheduler {

    private final TeamMapper teamMapper;
    private final TeamMemberMapper memberMapper;
    private final MailboxService mailboxService;
    private final TurnLifecycleService lifecycle;
    private final AgentChatService chatService;
    private final AgentRegistry registry;
    private final TeamProperties props;

    /** 成员 -> turn 订阅句柄(中断时 dispose)。 */
    private final Map<Long, Disposable> running = new ConcurrentHashMap<>();
    /** 成员 sn -> 调度锁(事件与定时循环并发保护的检查-派发原子性)。 */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${myagent.team.reconcile-interval-ms:2000}")
    public void reconcileAll() {
        for (Team team : teamMapper.selectAll()) {
            if (!"ACTIVE".equals(team.getStatus())) {
                continue;
            }
            List<TeamMember> members = memberMapper.selectListByQuery(QueryWrapper.create()
                    .where(TeamMemberTableDef.TEAM_MEMBER.TEAM_ID.eq(team.getId()))
                    .and(TeamMemberTableDef.TEAM_MEMBER.STATUS.eq(TeamMember.STATUS_ACTIVE)));
            for (TeamMember member : members) {
                try {
                    tryReconcile(team, member);
                } catch (Exception e) {
                    log.error("[team] reconcile {} failed", member.getAgentSn(), e);
                }
            }
        }
    }

    /** 信箱写入即触发(主路径):分配/消息 -> 唤醒。 */
    @EventListener
    public void onMailboxDelivered(MailboxService.MailboxDelivered event) {
        Team team = teamMapper.selectOneById(event.teamId());
        if (team == null) {
            return;
        }
        TeamMember member = memberMapper.selectOneByQuery(QueryWrapper.create()
                .where(TeamMemberTableDef.TEAM_MEMBER.TEAM_ID.eq(event.teamId()))
                .and(TeamMemberTableDef.TEAM_MEMBER.AGENT_SN.eq(event.agentSn()))
                .limit(1));
        if (member != null) {
            tryReconcile(team, member);
        }
    }

    /** 外部恢复入口:成员槽位恢复(如被 Leader resume)后,立即对账处理信箱积压。 */
    public void kick(Long teamId, String agentSn) {
        Team team = teamMapper.selectOneById(teamId);
        if (team == null) {
            return;
        }
        TeamMember member = memberMapper.selectOneByQuery(QueryWrapper.create()
                .where(TeamMemberTableDef.TEAM_MEMBER.TEAM_ID.eq(teamId))
                .and(TeamMemberTableDef.TEAM_MEMBER.AGENT_SN.eq(agentSn))
                .limit(1));
        if (member != null) {
            tryReconcile(team, member);
        }
    }

    private void tryReconcile(Team team, TeamMember member) {
        String sn = member.getAgentSn();
        if (!registry.exists(sn)) {
            return;
        }
        synchronized (locks.computeIfAbsent(sn, k -> new Object())) {
            // 槽位状态必须在锁内重读:failTurn 的错误回调跑在 reactor 线程(不持锁),
            // "暂停写入 + 通知投递触发的事件调度"可能与本方法交错,锁外检查会放行多余派发
            TeamMember current = memberMapper.selectOneById(member.getId());
            if (current == null || TeamMember.STATUS_PAUSED.equals(current.getStatus())) {
                return;
            }
            TeamTurn last = lifecycle.latestTurn(sn);
            if (last != null && TeamTurn.STATUS_RUNNING.equals(last.getStatus())) {
                if (lifecycle.isLeaseExpired(last)) {
                    // 僵死回收:消息保留未读,重试预算内则继续走到下面的重派
                    if (!lifecycle.failTurn(last, "lease expired")) {
                        return;
                    }
                } else {
                    return;
                }
            }
            List<MailboxMessage> batch = mailboxService.unread(sn, props.getWakeBatchSize());
            if (batch.isEmpty()) {
                return;
            }
            int previousAttempts = (last != null && TeamTurn.STATUS_FAILED.equals(last.getStatus()))
                    ? last.getDeliveryAttempts() : 0;
            dispatch(team, member, batch, previousAttempts);
        }
    }

    private void dispatch(Team team, TeamMember member, List<MailboxMessage> batch, int previousAttempts) {
        TeamTurn turn = lifecycle.startTurn(team, member, batch, previousAttempts);
        String wakePrompt = buildWakePrompt(team, member, batch);
        AgentProfile profile = AgentProfile.builder()
                .userId("team:" + team.getId())
                .sessionId(turn.getThreadId())
                .displayName(member.getDisplayName())
                .build();
        log.info("[team] dispatch turn#{} to {} with {} messages",
                turn.getId(), member.getAgentSn(), batch.size());

        StringBuilder transcript = new StringBuilder();
        Disposable disposable = chatService.stream(member.getAgentSn(), wakePrompt, profile)
                .doOnNext(output -> {
                    if (output instanceof StreamingOutput<?> streaming
                            && streaming.chunk() != null && !output.isEND()) {
                        transcript.append(streaming.chunk());
                    }
                })
                .subscribe(
                    null,
                    err -> {
                        running.remove(turn.getId());
                        lifecycle.failTurn(turn, err.getMessage() == null ? "stream error" : err.getMessage());
                    },
                    () -> {
                        running.remove(turn.getId());
                        lifecycle.completeTurn(turn, transcript.toString());
                    });
        running.put(turn.getId(), disposable);
    }

    /** 唤醒上下文 = 治理优先级栈中的"唤醒 payload + 当前任务上下文"层。 */
    private String buildWakePrompt(Team team, TeamMember member, List<MailboxMessage> batch) {
        StringBuilder sb = new StringBuilder();
        sb.append("【团队唤醒】你有 ").append(batch.size()).append(" 条未读消息:\n");
        for (MailboxMessage msg : batch) {
            sb.append("- [msg#").append(msg.getId()).append("|").append(msg.getMsgType()).append("] ")
              .append(msg.getContent()).append("\n");
            if (msg.getPayload() != null && !msg.getPayload().isBlank()) {
                sb.append("  payload: ").append(msg.getPayload()).append("\n");
            }
        }
        sb.append("\n处理规则:\n");
        sb.append("1. 用团队工具处理每条消息(任务用 team_task_update 推进状态,结果用 team_send_message 汇报给 ").append(team.getLeadSn()).append(");\n");
        sb.append("2. 处理完所有事项后直接结束回合(standing by = 结束回合),不要等待、不要轮询,新消息会再次唤醒你。\n");
        return sb.toString();
    }

    /** Leader 中断:掐断当前 turn + 把替换指令持久化为信箱最高优先级消息(下轮必然最先读)。 */
    public void interrupt(Long teamId, String agentSn, String replacementInstruction) {
        Disposable disposable = null;
        TeamTurn last = lifecycle.latestTurn(agentSn);
        if (last != null && TeamTurn.STATUS_RUNNING.equals(last.getStatus())) {
            disposable = running.remove(last.getId());
            last.setStatus(TeamTurn.STATUS_FAILED);
            last.setFinishedAt(java.time.LocalDateTime.now());
            // Leader 主动中断不计入投递重试预算
        }
        if (disposable != null) {
            disposable.dispose();
        }
        mailboxService.deliver(teamId, agentSn, MailboxMessage.TYPE_MESSAGE, 10,
                replacementInstruction, java.util.Map.of("interrupted", true));
    }
}
