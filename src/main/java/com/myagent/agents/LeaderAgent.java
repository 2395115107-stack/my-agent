package com.myagent.agents;

import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.myagent.config.ModelFactory;
import com.myagent.engine.AbstractTeamAgent;
import com.myagent.engine.AgentRegistry;
import com.myagent.engine.UsageInterceptor;
import com.myagent.team.tools.LeaderTeamTools;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Leader:唯一的 lead_only 工具持有者。治理优先级栈的"团队治理规则"层内嵌在提示词里。
 */
@Component
public class LeaderAgent extends AbstractTeamAgent {

    public static final String SN = "team-lead";
    private final LeaderTeamTools leaderTeamTools;

    public LeaderAgent(AgentRegistry registry, RedisSaver saver, ModelFactory modelFactory,
                       UsageInterceptor usageInterceptor, LeaderTeamTools leaderTeamTools) {
        super(registry, saver, modelFactory, usageInterceptor);
        this.leaderTeamTools = leaderTeamTools;
    }

    @Override
    public String sn() {
        return SN;
    }

    @Override
    public String systemPrompt() {
        try {
            return new String(new ClassPathResource("prompts/team-lead-governance.st")
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("leader governance prompt missing", e);
        }
    }

    @Override
    protected Object[] tools() {
        return new Object[]{leaderTeamTools};
    }
}
