package com.myagent.team.patrol.check;

import com.myagent.team.entity.Team;
import com.myagent.team.entity.TeamMember;
import com.myagent.team.entity.TeamTurn;
import com.myagent.team.entity.table.TeamMemberTableDef;
import com.myagent.team.entity.table.TeamTurnTableDef;
import com.myagent.team.mapper.TeamMemberMapper;
import com.myagent.team.mapper.TeamTurnMapper;
import com.myagent.team.patrol.PatrolCheck;
import com.myagent.team.patrol.PatrolProperties;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 连续回合失败:同一成员最近 N 个回合全部 FAILED(N = 连续失败阈值)。
 * 区别于单次 turn_failed 事件:连续失败意味着系统性故障(模型端点/Key/网络),不是偶发抖动。
 * 失败原因来自 tbl_team_turn.fail_reason(TurnLifecycleService 落库)。
 */
@Component
@RequiredArgsConstructor
public class TurnFailureCheck implements PatrolCheck {

    private final TeamMemberMapper memberMapper;
    private final TeamTurnMapper turnMapper;
    private final PatrolProperties props;

    @Override
    public String key() {
        return "turn_failure";
    }

    @Override
    public String description() {
        return "同一成员连续多个回合失败(系统性故障信号)";
    }

    @Override
    public List<PatrolItem> check(Team team) {
        List<PatrolItem> items = new ArrayList<>();
        List<TeamMember> members = memberMapper.selectListByQuery(QueryWrapper.create()
                .where(TeamMemberTableDef.TEAM_MEMBER.TEAM_ID.eq(team.getId())));
        for (TeamMember member : members) {
            String sn = member.getAgentSn();
            List<TeamTurn> recent = turnMapper.selectListByQuery(QueryWrapper.create()
                    .where(TeamTurnTableDef.TEAM_TURN.AGENT_SN.eq(sn))
                    .and(TeamTurnTableDef.TEAM_TURN.TEAM_ID.eq(team.getId()))
                    .orderBy(TeamTurnTableDef.TEAM_TURN.ID.desc())
                    .limit(props.getConsecutiveFailures()));
            if (recent.size() < props.getConsecutiveFailures()) {
                continue;
            }
            boolean allFailed = recent.stream().allMatch(t -> TeamTurn.STATUS_FAILED.equals(t.getStatus()));
            if (!allFailed) {
                continue;
            }
            String reason = recent.get(0).getFailReason();
            items.add(PatrolItem.critical("member:" + sn,
                    "成员 " + sn + " 连续 " + recent.size() + " 个回合失败"
                            + (reason == null || reason.isBlank() ? "" : ",最近原因:" + reason),
                    TeamMember.STATUS_PAUSED.equals(member.getStatus())
                            ? "槽位已暂停:先排查模型端点/Key,恢复后成员自动重试"
                            : "排查模型端点/Key/网络;若持续失败,暂停槽位并人工介入"));
        }
        return items;
    }
}
