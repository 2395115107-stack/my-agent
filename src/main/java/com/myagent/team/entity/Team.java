package com.myagent.team.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 团队。AionUi `teams` 表的等价物。
 */
@Data
@Table("tbl_team")
public class Team {
    @Id(keyType = KeyType.Auto)
    private Long id;
    private String name;
    private String leadSn;
    private String status;
    private LocalDateTime createdAt;
}
