package com.myagent.team.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 团队成员:注册表里的 Agent + 团队身份。
 * AionUi 用 CLI 进程 + slot_id;服务端成员 = AgentRegistry 里的对象,sn 即 slot_id。
 */
@Data
@NoArgsConstructor
@Table("tbl_team_member")
public class TeamMember {
    public static final String ROLE_LEAD = "LEAD";
    public static final String ROLE_MEMBER = "MEMBER";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_PAUSED = "PAUSED";

    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long teamId;
    private String agentSn;
    private String role;
    private String displayName;
    /** 成员系统提示词(spawn 时写入;Leader 是治理提示词) */
    private String systemPrompt;
    private String status;
    private LocalDateTime createdAt;
}
