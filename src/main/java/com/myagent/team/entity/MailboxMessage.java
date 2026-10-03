package com.myagent.team.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 信箱(分配怎么送达)。对齐 AionUi mailbox.rs:
 * per-agent FIFO;投递重试/已读语义见 TurnLifecycleService —— 只有 turn 成功完成才标已读。
 */
@Data
@NoArgsConstructor
@Table("tbl_mailbox")
public class MailboxMessage {
    public static final String TYPE_MESSAGE = "MESSAGE";
    public static final String TYPE_TASK_ASSIGN = "TASK_ASSIGN";
    public static final String TYPE_IDLE_NOTIFY = "IDLE_NOTIFY";
    public static final String TYPE_TASK_FAILED = "TASK_FAILED";
    public static final String TYPE_SHUTDOWN_REQ = "SHUTDOWN_REQ";

    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long teamId;
    private String agentSn;
    private String msgType;
    /** 优先级:interrupt 的替换指令为 10,普通 0;读取排序 priority DESC, id ASC */
    private Integer priority;
    private String content;
    /** JSON 附加数据(任务详情等) */
    private String payload;
    private Boolean isRead;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;
}
