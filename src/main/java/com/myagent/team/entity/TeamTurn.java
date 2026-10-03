package com.myagent.team.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 成员 turn 租约记录(谁来执行、何时回收)。
 * 对齐 AionUi scheduler/crash_recovery + lease_expires_in_ms:超时视为僵死,回收后消息保留未读重投。
 */
@Data
@NoArgsConstructor
@Table("tbl_team_turn")
public class TeamTurn {
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";

    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long teamId;
    private String agentSn;
    /** = 成员稳定会话 ID team_{teamId}_{sn},挂 Redis 检查点 */
    private String threadId;
    private String status;
    /** 本次 turn 注入的消息 ID JSON 数组;成功后按此精确标已读 */
    private String deliveredMessageIds;
    private Integer deliveryAttempts;
    private LocalDateTime startedAt;
    private LocalDateTime leaseExpiresAt;
    private LocalDateTime finishedAt;
}
