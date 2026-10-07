package com.myagent.team.patrol.check;

import com.myagent.team.TurnLifecycleService;
import com.myagent.team.entity.Team;
import com.myagent.team.entity.TeamMember;
import com.myagent.team.entity.TeamTask;
import com.myagent.team.entity.TeamTurn;
import com.myagent.team.entity.table.TeamMemberTableDef;
import com.myagent.team.mapper.TeamMemberMapper;
import com.myagent.team.mapper.TeamTaskMapper;
import com.myagent.team.patrol.PatrolCheck;
import com.myagent.team.patrol.PatrolProperties;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.myagent.team.entity.table.TeamTaskTableDef.TEAM_TASK;

/**
 * 停滞任务:IN_PROGRESS 超过阈值无进展,且 owner 当前没有进行中的回合。
 * - owner 已暂停 -> CRITICAL(任务实际无人推进);
 * - owner 不在花名册 -> CRITICAL(幽灵任务);
 * - 其余 -> WARN 并声明自动催办动作(nudge_owner,由编排器执行)。
 */
@Component
@RequiredArgsConstructor
public class StalledTaskCheck implements PatrolCheck {

    public static final String ACTION_NUDGE_OWNER = "nudge_owner";

    private final TeamTaskMapper taskMapper;
    private final TeamMemberMapper memberMapper;
    private final TurnLifecycleService lifecycle;
    private final PatrolProperties props;

    @Override
    public String key() {
        return "stalled_task";
    }

    @Override
    public String description() {
        return "IN_PROGRESS 任务长时间无进展且 owner 空闲";
    }

    @Override
    public List<PatrolItem> check(Team team) {
        List<PatrolItem> items = new ArrayList<>();
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(props.getStalledMinutes());
        List<TeamTask> doing = taskMapper.selectListByQuery(QueryWrapper.create()
                .where(TEAM_TASK.TEAM_ID.eq(team.getId()))
                .and(TEAM_TASK.STATUS.eq(TeamTask.STATUS_IN_PROGRESS)));
        for (TeamTask task : doing) {
            if (task.getOwnerSn() == null || task.getOwnerSn().isBlank()
                    || (task.getUpdatedAt() != null && task.getUpdatedAt().isAfter(deadline))) {
                continue;
            }
            String sn = task.getOwnerSn();
            TeamMember member = findMember(team.getId(), sn);
            TeamTurn last = lifecycle.latestTurn(sn);
            boolean ownerBusy = last != null && TeamTurn.STATUS_RUNNING.equals(last.getStatus())
                    && !lifecycle.isLeaseExpired(last);
            if (ownerBusy) {
                continue;
            }
            long stalledMin = task.getUpdatedAt() == null ? -1
                    : Duration.between(task.getUpdatedAt(), LocalDateTime.now()).toMinutes();
            String subject = "task#" + task.getId();
            // 关联实体 = owner:同成员的暂停/积压/连续失败告警与本条聚合为一次通知(同根因)
            String entity = "member:" + sn;
            if (member == null) {
                items.add(PatrolItem.critical(subject,
                        "任务「" + task.getSubject() + "」停滞 " + stalledMin + " 分钟:owner " + sn + " 不在本团队花名册",
                        "检查该 Agent 是否仍在注册表,或把任务改派给其他成员").withEntity(entity));
                continue;
            }
            if (TeamMember.STATUS_PAUSED.equals(member.getStatus())) {
                items.add(PatrolItem.critical(subject,
                        "任务「" + task.getSubject() + "」停滞 " + stalledMin + " 分钟:owner " + sn + " 槽位已暂停",
                        "恢复成员槽位(resume)后其会自动处理信箱积压").withEntity(entity));
                continue;
            }
            items.add(PatrolItem.warn(subject,
                    "任务「" + task.getSubject() + "」停滞 " + stalledMin + " 分钟,owner " + sn + " 空闲",
                    "催办 owner 继续推进,或核对任务是否实际已完成(状态未更新)")
                    .withAction(ACTION_NUDGE_OWNER).withEntity(entity));
        }
        return items;
    }

    private TeamMember findMember(Long teamId, String sn) {
        List<TeamMember> hit = memberMapper.selectListByQuery(QueryWrapper.create()
                .where(TeamMemberTableDef.TEAM_MEMBER.TEAM_ID.eq(teamId))
                .and(TeamMemberTableDef.TEAM_MEMBER.AGENT_SN.eq(sn))
                .limit(1));
        return hit.isEmpty() ? null : hit.get(0);
    }
}
