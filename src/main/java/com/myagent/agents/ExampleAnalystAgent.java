package com.myagent.agents;

import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.myagent.config.ModelFactory;
import com.myagent.engine.AbstractTeamAgent;
import com.myagent.engine.AgentRegistry;
import com.myagent.engine.UsageInterceptor;
import com.myagent.plugin.PluginService;
import com.myagent.team.MailboxService;
import com.myagent.team.TaskBoardService;
import com.myagent.team.TeamToolSupport;
import com.myagent.team.tools.MemberTeamTools;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 示例成员:预置注册,通过 POST /api/team/{id}/members?agentSn=analyst-01 加入团队;
 * 运行时动态成员走 SpawnService(Leader 的 teamSpawnAgent)。
 */
@Component
public class ExampleAnalystAgent extends AbstractTeamAgent {

    public static final String SN = "analyst-01";
    private final TeamToolSupport support;
    private final TaskBoardService taskBoardService;
    private final MailboxService mailboxService;

    public ExampleAnalystAgent(AgentRegistry registry, RedisSaver saver, ModelFactory modelFactory,
                               UsageInterceptor usageInterceptor, PluginService pluginService,
                               TeamToolSupport support,
                               TaskBoardService taskBoardService, MailboxService mailboxService) {
        super(registry, saver, modelFactory, usageInterceptor, pluginService);
        this.support = support;
        this.taskBoardService = taskBoardService;
        this.mailboxService = mailboxService;
    }

    @Override
    public String sn() {
        return SN;
    }

    @Override
    public String systemPrompt() {
        try {
            String base = new String(new ClassPathResource("prompts/team-member-base.st")
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return base + "\n\n# 你的职责\n你是数据分析成员,擅长 SQL 查询与统计分析。"
                    + "接到数据分析任务后:拆解问题 -> 给出结论与依据 -> 标注数据假设。";
        } catch (Exception e) {
            throw new IllegalStateException("member base prompt missing", e);
        }
    }

    @Override
    protected Object[] tools() {
        return new Object[]{new MemberTeamTools(SN, support, taskBoardService, mailboxService)};
    }
}
