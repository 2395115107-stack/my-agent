package com.myagent.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.team.entity.Team;
import com.myagent.team.entity.TeamMember;
import com.myagent.team.entity.table.TeamMemberTableDef;
import com.myagent.team.mapper.TeamMapper;
import com.myagent.team.mapper.TeamMemberMapper;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 工具面公共支撑:当前团队解析、花名册、消息格式化。
 * MVP 约束:单活跃团队(首个 status=ACTIVE 的团队);多团队时工具需携带 teamId 并按成员归属校验。
 */
@Component
@RequiredArgsConstructor
public class TeamToolSupport {

    private final TeamMapper teamMapper;
    private final TeamMemberMapper memberMapper;
    private final MailboxService mailboxService;
    private final ObjectMapper objectMapper;

    public Team currentTeam() {
        List<Team> teams = teamMapper.selectListByQuery(QueryWrapper.create()
                .where(com.myagent.team.entity.table.TeamTableDef.TEAM.STATUS.eq("ACTIVE"))
                .limit(1));
        if (teams.isEmpty()) {
            throw new NoSuchElementException("no active team; create one via POST /api/team first");
        }
        return teams.get(0);
    }

    public List<Map<String, Object>> membersPayload(Long teamId) {
        return memberMapper.selectListByQuery(QueryWrapper.create()
                        .where(TeamMemberTableDef.TEAM_MEMBER.TEAM_ID.eq(teamId)))
                .stream()
                .map(m -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("sn", m.getAgentSn());
                    row.put("displayName", m.getDisplayName());
                    row.put("role", m.getRole());
                    row.put("status", m.getStatus());
                    row.put("unread", mailboxService.unreadCount(m.getAgentSn()));
                    return row;
                })
                .toList();
    }

    public String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    public TeamMember requireMember(Long teamId, String sn) {
        TeamMember member = memberMapper.selectOneByQuery(QueryWrapper.create()
                .where(TeamMemberTableDef.TEAM_MEMBER.TEAM_ID.eq(teamId))
                .and(TeamMemberTableDef.TEAM_MEMBER.AGENT_SN.eq(sn))
                .limit(1));
        if (member == null) {
            throw new NoSuchElementException("agent not in team: " + sn);
        }
        return member;
    }
}
