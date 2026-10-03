package com.myagent.engine;

import lombok.Builder;
import lombok.Data;

/**
 * 调用者/运行者身份,贯穿 RunnableConfig metadata(对齐 Phoenix UserProfile 的最小集)。
 */
@Data
@Builder
public class AgentProfile {
    private String userId;
    private String sessionId;
    private String displayName;
}
