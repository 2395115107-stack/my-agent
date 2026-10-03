package com.myagent.team;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.team.entity.MailboxMessage;
import com.myagent.team.entity.TeamTask;
import com.myagent.team.mapper.TeamTaskMapper;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

import static com.myagent.team.entity.table.TeamTaskTableDef.TEAM_TASK;

/**
 * 任务板:分配什么。对齐 AionUi task_board.rs:
 * - 状态机 PENDING -> IN_PROGRESS -> COMPLETED(DELETED 软删)
 * - 分配即唤醒:create 带 owner 时写信箱 TASK_ASSIGN,调度器据此唤醒成员
 * - 依赖解锁:任务 COMPLETED 时检查下游 blocked 任务,全部依赖就绪则通知其 owner
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskBoardService {

    private final TeamTaskMapper taskMapper;
    private final MailboxService mailboxService;
    private final TeamEventPublisher events;
    private final ObjectMapper objectMapper;

    public TeamTask create(Long teamId, String subject, String description, String ownerSn,
                           List<Long> blockedBy, String createdBySn) {
        TeamTask task = new TeamTask();
        task.setTeamId(teamId);
        task.setSubject(subject);
        task.setDescription(description);
        task.setOwnerSn(ownerSn);
        task.setBlockedBy(toJson(blockedBy));
        task.setStatus(TeamTask.STATUS_PENDING);
        task.setCreatedBySn(createdBySn);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.insert(task);

        if (ownerSn != null && !ownerSn.isBlank()) {
            // 分配即唤醒:无需再额外 sendMessage 交接(AionUi 治理规则第一条)
            mailboxService.deliver(teamId, ownerSn, MailboxMessage.TYPE_TASK_ASSIGN, 0,
                    "新任务分配 #" + task.getId() + " " + subject,
                    java.util.Map.of("taskId", task.getId(), "subject", subject, "description", description));
        }
        events.publish("task_changed", teamId, "created:" + task.getId());
        return task;
    }

    public TeamTask update(Long taskId, String newStatus, String actorSn) {
        TeamTask task = taskMapper.selectOneById(taskId);
        if (task == null || TeamTask.STATUS_DELETED.equals(task.getStatus())) {
            throw new IllegalArgumentException("task not found: " + taskId);
        }
        task.setStatus(newStatus);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.update(task);
        events.publish("task_changed", task.getTeamId(), task.getId() + ":" + newStatus);

        if (TeamTask.STATUS_COMPLETED.equals(newStatus)) {
            unblockDependents(task);
        }
        return task;
    }

    /** 依赖解锁:下游 PENDING 任务的所有依赖都 COMPLETED 时,给其 owner 投递解锁通知。 */
    private void unblockDependents(TeamTask completed) {
        List<TeamTask> candidates = taskMapper.selectListByQuery(QueryWrapper.create()
                .where(TEAM_TASK.TEAM_ID.eq(completed.getTeamId()))
                .and(TEAM_TASK.STATUS.eq(TeamTask.STATUS_PENDING)));
        for (TeamTask downstream : candidates) {
            List<Long> deps = fromJson(downstream.getBlockedBy());
            if (deps == null || !deps.contains(completed.getId())) {
                continue;
            }
            boolean allDone = deps.stream().allMatch(depId -> {
                TeamTask dep = taskMapper.selectOneById(depId);
                return dep != null && TeamTask.STATUS_COMPLETED.equals(dep.getStatus());
            });
            if (allDone && downstream.getOwnerSn() != null) {
                mailboxService.deliver(downstream.getTeamId(), downstream.getOwnerSn(),
                        MailboxMessage.TYPE_MESSAGE, 1,
                        "依赖已解锁:任务 #" + downstream.getId() + " " + downstream.getSubject() + " 可以开始",
                        java.util.Map.of("taskId", downstream.getId()));
            }
        }
    }

    public List<TeamTask> list(Long teamId, String ownerSn, String status) {
        QueryWrapper q = QueryWrapper.create().where(TEAM_TASK.TEAM_ID.eq(teamId));
        if (ownerSn != null && !ownerSn.isBlank()) {
            q.and(TEAM_TASK.OWNER_SN.eq(ownerSn));
        }
        if (status != null && !status.isBlank()) {
            q.and(TEAM_TASK.STATUS.eq(status));
        }
        return taskMapper.selectListByQuery(q.orderBy(TEAM_TASK.ID.asc()));
    }

    public TeamTask get(Long taskId) {
        return taskMapper.selectOneById(taskId);
    }

    private String toJson(List<Long> ids) {
        try {
            return ids == null ? null : objectMapper.writeValueAsString(ids);
        } catch (Exception e) {
            throw new IllegalStateException("blockedBy serialize failed", e);
        }
    }

    private List<Long> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            log.warn("blockedBy parse failed: {}", json);
            return List.of();
        }
    }
}
