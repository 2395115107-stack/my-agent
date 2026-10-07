package com.myagent.team.patrol;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 巡查发现记录。去重键 = (teamId, checkKey, subject);
 * 状态机 OPEN -> RESOLVED(巡检不再命中)/ OPEN -> ACK(用户认领,停止通知)/ ACK -> RESOLVED;
 * RESOLVED 后再次命中则重开(occurrence 连续计数)。
 */
@Data
@NoArgsConstructor
@Table("tbl_patrol_finding")
public class PatrolFinding {
    public static final String SEVERITY_INFO = "INFO";
    public static final String SEVERITY_WARN = "WARN";
    public static final String SEVERITY_CRITICAL = "CRITICAL";

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_ACK = "ACK";
    public static final String STATUS_RESOLVED = "RESOLVED";

    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long teamId;
    private String checkKey;
    private String severity;
    private String subject;
    private String detail;
    private String suggestion;
    /** 最近一次自动处置的描述(如 "已催办 owner analyst-01");仅建议无动作时为 NULL */
    private String autoAction;
    private String status;
    private Integer occurrenceCount;
    private LocalDateTime firstSeenAt;
    private LocalDateTime lastSeenAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime notifiedAt;
}
