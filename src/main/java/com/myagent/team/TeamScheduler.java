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
import com.myagent.team.mapper.PatrolFindingMapper;
import com.myagent.team.mapper.TeamMapper;
import com.myagent.team.mapper.TeamMemberMapper;
import com.myagent.team.patrol.PatrolFinding;
import com.myagent.team.patrol.PatrolProperties;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 调度器:谁来执行、何时唤醒。AionUi 三件套之三(scheduler/member_runtime)的服务端化:
 *
 * - 桌面版的"懒唤醒进程"不搬:服务端成员是注册表里的无状态对象,按需调用即可;
 * - 保留协议纪律:【等待 = 结束 turn,唤醒 = 信箱有新消息】;每成员同一时刻最多一个 turn;
 * - 双通道调度:信箱写入事件(主路径,@EventListener)+ 定时对账(兜底,@Scheduled);
 * - lease:turn 带租约,过期视为僵死 -> 失败回收,消息保留未读重投,超限暂停槽位;
 * - 可靠性模式(2025+ agent runtime 惯例,见 README 文献表):失败重投按指数退避+抖动排队,
 *   避免对账循环 2s 一次的固定节奏对故障端点形成重试风暴;暂停槽位按熔断半开语义到期自动探活。
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
    private final PatrolProperties patrolProps;
    private final PatrolFindingMapper findingMapper;
    private final TeamEventPublisher events;

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
            probePausedMembers(team);
        }
    }

    /**
     * 熔断半开自动探活(R2):PAUSED 槽位在退避探活窗(随连续暂停次数指数增长)到期后
     * 自动转 ACTIVE 并立即对账 —— 一个探活回合,失败则重试预算立即超限回到 PAUSED,
     * 探活窗翻倍。人工 resume 仍是最高优先级,会把计数清零。
     */
    private void probePausedMembers(Team team) {
        if (!patrolProps.isAutoProbePaused()) {
            return;
        }
        List<TeamMember> paused = memberMapper.selectListByQuery(QueryWrapper.create()
                .where(TeamMemberTableDef.TEAM_MEMBER.TEAM_ID.eq(team.getId()))
                .and(TeamMemberTableDef.TEAM_MEMBER.STATUS.eq(TeamMember.STATUS_PAUSED)));
        for (TeamMember member : paused) {
            LocalDateTime due = member.getPausedAt() == null ? LocalDateTime.now()
                    : member.getPausedAt().plusSeconds(probeCooldownSeconds(member.getPauseCount(),
                            patrolProps.getProbeBaseSeconds(), patrolProps.getProbeCapSeconds()));
            if (due.isAfter(LocalDateTime.now())) {
                continue;
            }
            member.setStatus(TeamMember.STATUS_ACTIVE);
            memberMapper.update(member);
            int count = member.getPauseCount() == null ? 1 : member.getPauseCount();
            log.warn("[team] circuit half-open: probe paused member {} (pauseCount={})",
                    member.getAgentSn(), count);
            events.publish("agent_status_changed", team.getId(),
                    member.getAgentSn() + ":ACTIVE(probe)");
            tryReconcile(team, member);
        }
    }

    /** 探活冷却 = probe-base * 2^(pauseCount-1),封顶 probe-cap(经典断路器退避)。 */
    static long probeCooldownSeconds(Integer pauseCount, long baseSeconds, long capSeconds) {
        int count = pauseCount == null || pauseCount < 1 ? 1 : pauseCount;
        return Math.min(capSeconds, baseSeconds << Math.min(count - 1, 20));
    }

    /** 失败重投的退避等待(R1):base * 2^(attempts-1) + ±20% 抖动,封顶 cap;首次投递不等待。 */
    static long nextRetryDelayMs(int attempts, long baseMs, long capMs) {
        if (attempts <= 1) {
            return 0;
        }
        long exp = Math.min(capMs, baseMs << Math.min(attempts - 2, 20));
        double jitter = ThreadLocalRandom.current().nextDouble(0.8, 1.2);
        return (long) Math.min(capMs, exp * jitter);
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
            // R1 指数退避:失败重投不按对账节奏(2s)立刻重试,按退避曲线排队,防重试风暴
            if (previousAttempts > 0 && last.getFinishedAt() != null) {
                long delayMs = nextRetryDelayMs(previousAttempts,
                        props.getRetryBackoffBaseSeconds() * 1000L, props.getRetryBackoffCapSeconds() * 1000L);
                if (last.getFinishedAt().plusNanos(delayMs * 1_000_000L).isAfter(LocalDateTime.now())) {
                    return;
                }
            }
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

    /** 唤醒上下文 = 治理优先级栈中的"唤醒 payload + 当前任务上下文"层;Leader 额外注入巡查知识(MAPE-K Knowledge)。 */
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
        if (TeamMember.ROLE_LEAD.equals(member.getRole())) {
            appendPatrolBriefing(sb, team);
        }
        return sb.toString();
    }

    /** MAPE-K Knowledge 注入(Magentic-One 进度账本的系统侧对应物):Leader 唤醒时附带当前巡查发现,支撑重排决策。 */
    private void appendPatrolBriefing(StringBuilder sb, Team team) {
        if (!patrolProps.isInjectWake()) {
            return;
        }
        try {
            List<PatrolFinding> open = findingMapper.selectListByQuery(QueryWrapper.create()
                    .where(com.myagent.team.patrol.table.PatrolFindingTableDef.PATROL_FINDING.TEAM_ID.eq(team.getId()))
                    .and(com.myagent.team.patrol.table.PatrolFindingTableDef.PATROL_FINDING.STATUS.eq(PatrolFinding.STATUS_OPEN))
                    .orderBy(com.myagent.team.patrol.table.PatrolFindingTableDef.PATROL_FINDING.ID.desc())
                    .limit(5));
            if (open.isEmpty()) {
                return;
            }
            sb.append("\n【巡查简报】系统自动巡检当前有 ").append(open.size()).append(" 项未解决发现(主线任务优先,供重排参考):\n");
            for (PatrolFinding f : open) {
                sb.append("- [").append(f.getSeverity()).append("|").append(f.getCheckKey()).append("] ")
                  .append(f.getSubject()).append(":").append(f.getDetail()).append("\n");
            }
        } catch (Exception e) {
            log.warn("[team] patrol briefing skipped: {}", e.getMessage());
        }
    }

    /** Leader 中断:掐断当前 turn + 把替换指令持久化为信箱最高优先级消息(下轮必然最先读)。 */
    public void interrupt(Long teamId, String agentSn, String replacementInstruction) {
        Disposable disposable = null;
        TeamTurn last = lifecycle.latestTurn(agentSn);
        if (last != null && TeamTurn.STATUS_RUNNING.equals(last.getStatus())) {
            disposable = running.remove(last.getId());
            // 必须落库:否则 DB 里 turn 保持 RUNNING,替换指令要等租约过期才会被派发
            lifecycle.abortTurn(last);
            // Leader 主动中断不计入投递重试预算
        }
        if (disposable != null) {
            disposable.dispose();
        }
        mailboxService.deliver(teamId, agentSn, MailboxMessage.TYPE_MESSAGE, 10,
                replacementInstruction, java.util.Map.of("interrupted", true));
    }
}
