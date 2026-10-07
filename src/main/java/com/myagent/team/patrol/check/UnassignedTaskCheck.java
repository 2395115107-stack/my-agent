package com.myagent.team.patrol.check;

import com.myagent.team.entity.Team;
import com.myagent.team.entity.TeamTask;
import com.myagent.team.mapper.TeamTaskMapper;
import com.myagent.team.patrol.PatrolCheck;
import com.myagent.team.patrol.PatrolProperties;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.myagent.team.entity.table.TeamTaskTableDef.TEAM_TASK;

/** 无主任务:PENDING 且未分配,滞留超过阈值 —— Leader 拆解后忘记分配或分配链路中断的信号。 */
@Component
@RequiredArgsConstructor
public class UnassignedTaskCheck implements PatrolCheck {

    private final TeamTaskMapper taskMapper;
    private final PatrolProperties props;

    @Override
    public String key() {
        return "unassigned_task";
    }

    @Override
    public String description() {
        return "PENDING 任务长时间无人认领";
    }

    @Override
    public List<PatrolItem> check(Team team) {
        List<PatrolItem> items = new ArrayList<>();
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(props.getUnassignedMinutes());
        List<TeamTask> pending = taskMapper.selectListByQuery(QueryWrapper.create()
                .where(TEAM_TASK.TEAM_ID.eq(team.getId()))
                .and(TEAM_TASK.STATUS.eq(TeamTask.STATUS_PENDING)));
        for (TeamTask task : pending) {
            boolean noOwner = task.getOwnerSn() == null || task.getOwnerSn().isBlank();
            boolean stale = task.getCreatedAt() != null && task.getCreatedAt().isBefore(deadline);
            if (noOwner && stale) {
                items.add(PatrolItem.warn("task#" + task.getId(),
                        "任务「" + task.getSubject() + "」创建超过 " + props.getUnassignedMinutes()
                                + " 分钟仍未分配 owner",
                        "让 Leader 拆解分配,或确认该任务是否仍有必要(无必要可删除)"));
            }
        }
        return items;
    }
}
