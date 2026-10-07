package com.myagent.team.patrol.check;

import com.myagent.team.entity.Team;
import com.myagent.team.entity.TeamMember;
import com.myagent.team.entity.table.TeamMemberTableDef;
import com.myagent.team.mapper.TeamMemberMapper;
import com.myagent.team.patrol.PatrolCheck;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 暂停槽位:投递重试超限后被暂停的成员。暂停时已即时通知过 Leader,这里兜底防遗忘(通知走冷却窗)。 */
@Component
@RequiredArgsConstructor
public class PausedMemberCheck implements PatrolCheck {

    private final TeamMemberMapper memberMapper;

    @Override
    public String key() {
        return "paused_member";
    }

    @Override
    public String description() {
        return "成员槽位处于 PAUSED,等待人工恢复";
    }

    @Override
    public List<PatrolItem> check(Team team) {
        List<PatrolItem> items = new ArrayList<>();
        List<TeamMember> paused = memberMapper.selectListByQuery(QueryWrapper.create()
                .where(TeamMemberTableDef.TEAM_MEMBER.TEAM_ID.eq(team.getId()))
                .and(TeamMemberTableDef.TEAM_MEMBER.STATUS.eq(TeamMember.STATUS_PAUSED)));
        for (TeamMember member : paused) {
            items.add(PatrolItem.warn("member:" + member.getAgentSn(),
                    "成员 " + member.getAgentSn() + " 连续投递失败,槽位已暂停,信箱积压未处理",
                    "确认模型配置/端点可用后执行恢复(resume),成员会自动处理积压消息"));
        }
        return items;
    }
}
