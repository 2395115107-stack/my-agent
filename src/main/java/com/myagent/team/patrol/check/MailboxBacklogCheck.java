package com.myagent.team.patrol.check;

import com.myagent.engine.AgentRegistry;
import com.myagent.team.entity.MailboxMessage;
import com.myagent.team.entity.Team;
import com.myagent.team.entity.TeamMember;
import com.myagent.team.entity.TeamTurn;
import com.myagent.team.entity.table.MailboxMessageTableDef;
import com.myagent.team.entity.table.TeamMemberTableDef;
import com.myagent.team.entity.table.TeamTurnTableDef;
import com.myagent.team.mapper.MailboxMessageMapper;
import com.myagent.team.mapper.TeamMemberMapper;
import com.myagent.team.mapper.TeamTurnMapper;
import com.myagent.team.patrol.PatrolCheck;
import com.myagent.team.patrol.PatrolProperties;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 信箱积压:ACTIVE 成员有未读消息却长期空闲 —— 正常情况下调度对账循环(2s)必然派发,
 * 积压能存续说明派发链路有故障。两档:
 * - 成员不在注册表(幽灵) -> CRITICAL,调度器会跳过它,积压永远清不掉;
 * - 未读数量或最老未读年龄超阈 -> WARN。
 */
@Component
@RequiredArgsConstructor
public class MailboxBacklogCheck implements PatrolCheck {

    private final TeamMemberMapper memberMapper;
    private final MailboxMessageMapper mailboxMapper;
    private final TeamTurnMapper turnMapper;
    private final AgentRegistry registry;
    private final PatrolProperties props;

    @Override
    public String key() {
        return "mailbox_backlog";
    }

    @Override
    public String description() {
        return "成员信箱积压且无回合在处理";
    }

    @Override
    public List<PatrolItem> check(Team team) {
        List<PatrolItem> items = new ArrayList<>();
        List<TeamMember> actives = memberMapper.selectListByQuery(QueryWrapper.create()
                .where(TeamMemberTableDef.TEAM_MEMBER.TEAM_ID.eq(team.getId()))
                .and(TeamMemberTableDef.TEAM_MEMBER.STATUS.eq(TeamMember.STATUS_ACTIVE)));
        for (TeamMember member : actives) {
            String sn = member.getAgentSn();
            List<MailboxMessage> oldest = mailboxMapper.selectListByQuery(QueryWrapper.create()
                    .where(MailboxMessageTableDef.MAILBOX_MESSAGE.AGENT_SN.eq(sn))
                    .and(MailboxMessageTableDef.MAILBOX_MESSAGE.IS_READ.eq(false))
                    .orderBy(MailboxMessageTableDef.MAILBOX_MESSAGE.ID.asc())
                    .limit(1));
            if (oldest.isEmpty()) {
                continue;
            }
            long count = mailboxMapper.selectCountByQuery(QueryWrapper.create()
                    .where(MailboxMessageTableDef.MAILBOX_MESSAGE.AGENT_SN.eq(sn))
                    .and(MailboxMessageTableDef.MAILBOX_MESSAGE.IS_READ.eq(false)));
            boolean ghost = !registry.exists(sn);
            boolean busy = hasRunningTurn(sn);
            long ageMin = Duration.between(oldest.get(0).getCreatedAt(), LocalDateTime.now()).toMinutes();
            if (ghost) {
                items.add(PatrolItem.critical("member:" + sn,
                        "成员 " + sn + " 在花名册但未注册到 Agent 注册表,信箱积压 " + count
                                + " 条(最老 " + ageMin + " 分钟)无法派发",
                        "重启服务以恢复花名册注册,或将该成员移出团队"));
                continue;
            }
            if (busy) {
                continue;
            }
            if (count > props.getBacklogUnread() || ageMin > props.getBacklogAgeMinutes()) {
                items.add(PatrolItem.warn("member:" + sn,
                        "成员 " + sn + " 信箱积压 " + count + " 条(最老 " + ageMin
                                + " 分钟)却无回合在处理",
                        "查看事件流确认派发是否持续失败;必要时恢复/重试该成员"));
            }
        }
        return items;
    }

    private boolean hasRunningTurn(String sn) {
        List<TeamTurn> running = turnMapper.selectListByQuery(QueryWrapper.create()
                .where(TeamTurnTableDef.TEAM_TURN.AGENT_SN.eq(sn))
                .and(TeamTurnTableDef.TEAM_TURN.STATUS.eq(TeamTurn.STATUS_RUNNING))
                .limit(1));
        return !running.isEmpty();
    }
}
