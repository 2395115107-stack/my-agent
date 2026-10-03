package com.myagent.team;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.team.entity.MailboxMessage;
import com.myagent.team.entity.Team;
import com.myagent.team.entity.TeamMember;
import com.myagent.team.entity.TeamTurn;
import com.myagent.team.entity.table.TeamTurnTableDef;
import com.myagent.team.mapper.TeamMapper;
import com.myagent.team.mapper.TeamMemberMapper;
import com.myagent.team.mapper.TeamTurnMapper;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

import static com.myagent.team.entity.table.TeamTurnTableDef.TEAM_TURN;

/**
 * turn 生命周期:已读确认、idle 通知、投递重试预算、槽位暂停。
 * 可靠性语义对齐 AionUi:消息只有 turn 成功完成才标已读;重试超限 -> 槽位暂停 + 通知 Leader。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TurnLifecycleService {

    private final TeamTurnMapper turnMapper;
    private final TeamMemberMapper memberMapper;
    private final TeamMapper teamMapper;
    private final MailboxService mailboxService;
    private final TeamEventPublisher events;
    private final TeamProperties props;
    private final ObjectMapper objectMapper;

    public TeamTurn startTurn(Team team, TeamMember member, List<MailboxMessage> batch, int previousAttempts) {
        TeamTurn turn = new TeamTurn();
        turn.setTeamId(team.getId());
        turn.setAgentSn(member.getAgentSn());
        turn.setThreadId("team_" + team.getId() + "_" + member.getAgentSn());
        turn.setStatus(TeamTurn.STATUS_RUNNING);
        turn.setDeliveredMessageIds(toJson(batch.stream().map(MailboxMessage::getId).toList()));
        turn.setDeliveryAttempts(previousAttempts);
        turn.setStartedAt(LocalDateTime.now());
        turn.setLeaseExpiresAt(LocalDateTime.now().plusSeconds(props.getLeaseSeconds()));
        turnMapper.insert(turn);
        return turn;
    }

    /** 该成员最近一条 turn(RUNNING 即活跃;调用方自行判断租约是否过期)。 */
    public TeamTurn latestTurn(String agentSn) {
        List<TeamTurn> list = turnMapper.selectListByQuery(QueryWrapper.create()
                .where(TEAM_TURN.AGENT_SN.eq(agentSn))
                .orderBy(TEAM_TURN.ID.desc())
                .limit(1));
        return list.isEmpty() ? null : list.get(0);
    }

    public boolean isLeaseExpired(TeamTurn turn) {
        return turn.getLeaseExpiresAt() != null && turn.getLeaseExpiresAt().isBefore(LocalDateTime.now());
    }

    /**
     * turn 失败/僵死回收:消息保留未读(重投语义);返回 false 表示重试超限,槽位已暂停并通知 Leader。
     */
    public boolean failTurn(TeamTurn turn, String reason) {
        turn.setStatus(TeamTurn.STATUS_FAILED);
        turn.setDeliveryAttempts(turn.getDeliveryAttempts() == null ? 1 : turn.getDeliveryAttempts() + 1);
        turn.setFinishedAt(LocalDateTime.now());
        turnMapper.update(turn);
        log.warn("[team] turn#{} of {} failed: {}", turn.getId(), turn.getAgentSn(), reason);
        // 蓝图 6.2 的最小实现:失败即对团队事件流可见,不等重试超限
        events.publish("turn_failed", turn.getTeamId(), turn.getAgentSn() + ":" + reason);

        if (turn.getDeliveryAttempts() >= props.getDeliveryMaxAttempts()) {
            pauseAndNotify(turn);
            return false;
        }
        return true;
    }

    /** turn 成功:精确按注入的消息 ID 标已读 -> 投 Leader idle 通知 -> 事件。 */
    public void completeTurn(TeamTurn turn, String transcript) {
        turn.setStatus(TeamTurn.STATUS_DONE);
        turn.setFinishedAt(LocalDateTime.now());
        turnMapper.update(turn);

        List<Long> delivered = fromJson(turn.getDeliveredMessageIds());
        mailboxService.markRead(delivered);

        Team team = teamMapper.selectOneById(turn.getTeamId());
        if (team != null && !turn.getAgentSn().equals(team.getLeadSn())) {
            mailboxService.deliver(turn.getTeamId(), team.getLeadSn(), MailboxMessage.TYPE_IDLE_NOTIFY, 0,
                    "成员 " + turn.getAgentSn() + " turn 结束,已空闲", java.util.Map.of("from", turn.getAgentSn()));
        }
        events.publish("mailbox_changed", turn.getTeamId(), "turn_done:" + turn.getAgentSn());
        events.publish("teammate_message", turn.getTeamId(), transcript);
    }

    private void pauseAndNotify(TeamTurn turn) {
        TeamMember member = memberMapper.selectOneByQuery(QueryWrapper.create()
                .where(com.myagent.team.entity.table.TeamMemberTableDef.TEAM_MEMBER.AGENT_SN.eq(turn.getAgentSn()))
                .and(com.myagent.team.entity.table.TeamMemberTableDef.TEAM_MEMBER.TEAM_ID.eq(turn.getTeamId()))
                .limit(1));
        if (member != null) {
            member.setStatus(TeamMember.STATUS_PAUSED);
            memberMapper.update(member);
            events.publish("agent_status_changed", turn.getTeamId(), member.getAgentSn() + ":PAUSED");
        }
        Team team = teamMapper.selectOneById(turn.getTeamId());
        if (team != null) {
            mailboxService.deliver(turn.getTeamId(), team.getLeadSn(), MailboxMessage.TYPE_MESSAGE, 5,
                    "成员 " + turn.getAgentSn() + " 连续投递失败 " + turn.getDeliveryAttempts() + " 次,槽位已暂停,请人工介入",
                    java.util.Map.of("agentSn", turn.getAgentSn(), "attempts", turn.getDeliveryAttempts()));
        }
    }

    private String toJson(List<Long> ids) {
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (Exception e) {
            throw new IllegalStateException("serialize deliveredMessageIds failed", e);
        }
    }

    private List<Long> fromJson(String json) {
        try {
            return json == null ? List.of() : objectMapper.readValue(json, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
