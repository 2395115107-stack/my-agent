package com.myagent.team.patrol.check;

import com.myagent.team.entity.MailboxMessage;
import com.myagent.team.entity.Team;
import com.myagent.team.entity.TeamTask;
import com.myagent.team.entity.table.MailboxMessageTableDef;
import com.myagent.team.mapper.MailboxMessageMapper;
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

/**
 * 完成核验缺口(MAST「任务验证」失败模式的系统侧巡检,arXiv:2503.13657):
 * 任务已标 COMPLETED,但 owner 在完成前后没有发出任何 team_send_message 汇报。
 * 多智能体系统最常见的静默失败之一 —— 状态机走完了,产物没人核实。
 * 识别口径:tbl_mailbox 中 payload 含 "from":"<owner>" 的 MESSAGE(成员汇报走该路径;
 * IDLE_NOTIFY/任务分配不携带 from,不构成核验)。
 */
@Component
@RequiredArgsConstructor
public class UnverifiedCompletionCheck implements PatrolCheck {

    private final TeamTaskMapper taskMapper;
    private final MailboxMessageMapper mailboxMapper;
    private final PatrolProperties props;

    @Override
    public String key() {
        return "unverified_completion";
    }

    @Override
    public String description() {
        return "任务标记完成但 owner 无汇报,产物未经核验";
    }

    @Override
    public List<PatrolItem> check(Team team) {
        List<PatrolItem> items = new ArrayList<>();
        LocalDateTime since = LocalDateTime.now().minusMinutes(props.getUnverifiedLookbackMinutes());
        List<TeamTask> done = taskMapper.selectListByQuery(QueryWrapper.create()
                .where(TEAM_TASK.TEAM_ID.eq(team.getId()))
                .and(TEAM_TASK.STATUS.eq(TeamTask.STATUS_COMPLETED))
                .and(TEAM_TASK.UPDATED_AT.ge(since)));
        for (TeamTask task : done) {
            String owner = task.getOwnerSn();
            if (owner == null || owner.isBlank() || owner.equals(team.getLeadSn())
                    || task.getUpdatedAt() == null) {
                continue;
            }
            // 完成时刻前 1 分钟宽限:同回合内"先汇报后标完成"的顺序差异
            LocalDateTime windowStart = task.getUpdatedAt().minusMinutes(1);
            long reports = mailboxMapper.selectCountByQuery(QueryWrapper.create()
                    .where(MailboxMessageTableDef.MAILBOX_MESSAGE.TEAM_ID.eq(team.getId()))
                    .and(MailboxMessageTableDef.MAILBOX_MESSAGE.MSG_TYPE.eq(MailboxMessage.TYPE_MESSAGE))
                    .and(MailboxMessageTableDef.MAILBOX_MESSAGE.PAYLOAD
                            .like("\"from\":\"" + owner + "\""))
                    .and(MailboxMessageTableDef.MAILBOX_MESSAGE.CREATED_AT.ge(windowStart)));
            if (reports == 0) {
                items.add(PatrolItem.warn("task#" + task.getId(),
                        "任务「" + task.getSubject() + "」已标 COMPLETED,但 owner " + owner
                                + " 完成前后无任何汇报消息,产物未经核验",
                        "向 " + owner + " 索要产出并人工核验;持续缺失说明汇报纪律失守,需强化成员提示词"));
            }
        }
        return items;
    }
}
