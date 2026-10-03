package com.myagent.team;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.myagent.config.ModelFactory;
import com.myagent.engine.AgentRegistry;
import com.myagent.engine.UsageInterceptor;
import com.myagent.team.tools.MemberTeamTools;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 成员供给(对齐 AionUi provisioning.rs 的服务端等价物):
 * spawn = 动态构建 ReactAgent(治理提示词 + 成员专属职责 + 成员工具集)并注册进 AgentRegistry。
 * 动态成员的 Agent 本体是内存态,重启后从花名册的 system_prompt 重建注册,否则重启后成员"有名无实"。
 * 治理约束(正式版):Leader 必须先文字提案、等用户确认后才可调用(接 HITL)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpawnService {

    private final AgentRegistry registry;
    private final ModelFactory modelFactory;
    private final RedisSaver saver;
    private final UsageInterceptor usageInterceptor;
    private final TeamToolSupport support;
    private final TaskBoardService taskBoardService;
    private final MailboxService mailboxService;
    private final com.myagent.team.mapper.TeamMemberMapper memberMapper;

    @jakarta.annotation.PostConstruct
    void restorePersistedMembers() {
        try {
            List<com.myagent.team.entity.TeamMember> spawned = memberMapper.selectListByQuery(
                    com.mybatisflex.core.query.QueryWrapper.create()
                            .where(com.myagent.team.entity.table.TeamMemberTableDef.TEAM_MEMBER.SYSTEM_PROMPT.isNotNull())
                            .and(com.myagent.team.entity.table.TeamMemberTableDef.TEAM_MEMBER.SYSTEM_PROMPT.ne("")));
            int restored = 0;
            for (com.myagent.team.entity.TeamMember m : spawned) {
                if (registry.exists(m.getAgentSn())) {
                    continue;
                }
                registerMember(m.getAgentSn(), m.getSystemPrompt());
                restored++;
            }
            if (restored > 0) {
                log.info("[spawn] 已从花名册恢复 {} 个动态成员的注册", restored);
            }
        } catch (Exception e) {
            log.warn("[spawn] 动态成员恢复失败,以启动期注册表为准", e);
        }
    }

    /** 动态成员的 Agent 本体是内存态,重启后从花名册的 system_prompt 重建注册。 */
    public com.myagent.team.entity.TeamMember spawn(Long teamId, String displayName, String specialtyPrompt) {
        String sn = "member-" + UUID.randomUUID().toString().substring(0, 8);
        String base = readBasePrompt();
        String prompt = base + "\n\n# 你的职责\n" + specialtyPrompt;
        registerMember(sn, prompt);

        com.myagent.team.entity.TeamMember member = new com.myagent.team.entity.TeamMember();
        member.setTeamId(teamId);
        member.setAgentSn(sn);
        member.setRole(com.myagent.team.entity.TeamMember.ROLE_MEMBER);
        member.setDisplayName(displayName);
        member.setSystemPrompt(prompt);
        member.setStatus(com.myagent.team.entity.TeamMember.STATUS_ACTIVE);
        member.setCreatedAt(LocalDateTime.now());
        memberMapper.insert(member);
        return member;
    }

    private void registerMember(String sn, String systemPrompt) {
        ReactAgent agent = ReactAgent.builder()
                .name(sn)
                .model(modelFactory.chatModel())
                .systemPrompt(systemPrompt)
                .methodTools(new MemberTeamTools(sn, support, taskBoardService, mailboxService))
                .interceptors(List.of(usageInterceptor))
                .saver(saver)
                .build();
        registry.register(sn, agent);
    }

    private String readBasePrompt() {
        try {
            return new String(new ClassPathResource("prompts/team-member-base.st").getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("member base prompt missing", e);
        }
    }
}
