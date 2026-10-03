package com.myagent.team.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 团队任务板(分配的基本单位)。对齐 AionUi task_board.rs / team_tasks 表:
 * owner 指向成员 sn,blocked_by 是依赖任务 ID 的 JSON 数组,状态机 pending -> in_progress -> completed。
 */
@Data
@NoArgsConstructor
@Table("tbl_team_task")
public class TeamTask {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_DELETED = "DELETED";

    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long teamId;
    private String subject;
    private String description;
    /** 目标成员 sn;为空 = 未分配(看板 fallbackLane) */
    private String ownerSn;
    /** JSON 数组,如 "[3,7]":依赖的任务 ID */
    private String blockedBy;
    private String status;
    private String createdBySn;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
