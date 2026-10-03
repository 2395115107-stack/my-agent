package com.myagent.config;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 脚本化 Mock 模型(myagent.model.mock=true / LLM_MOCK=true 时启用):
 * 按真实工具协议发 tool call,把「拆解-分配-执行-汇报」全链路在无 API Key 的情况下跑通,供演示与联调。
 *
 * Leader 脚本:查花名册 -> teamTaskCreate(分配即唤醒) -> 文本收尾;
 *              唤醒内容含 IDLE_NOTIFY(成员完工)时不再拆新任务,直接向用户汇报。
 * 成员脚本  :teamTaskUpdate(IN_PROGRESS) -> teamTaskUpdate(COMPLETED) + teamSendMessage 汇报 -> 文本收尾。
 * 非唤醒路径(直接对话):返回说明文本。
 */
public class MockScriptedChatModel implements ChatModel {

    private static final Pattern TASK_ID = Pattern.compile("新任务分配 #(\\d+)");
    private static final Pattern GOAL_LINE = Pattern.compile("- \\[msg#\\d+\\|MESSAGE\\] (.*)");
    private static final Pattern MEMBER_SN = Pattern.compile("\"sn\":\"([^\"]+)\"");

    @Override
    public ChatResponse call(Prompt prompt) {
        List<Message> msgs = prompt.getInstructions();
        String system = msgs.stream().filter(SystemMessage.class::isInstance).findFirst()
                .map(m -> ((SystemMessage) m).getText()).orElse("");
        Message last = msgs.get(msgs.size() - 1);
        // 工具结果回传步里 last 是 ToolResponseMessage;唤醒/目标文本取历史里最近的 UserMessage
        String userText = lastUserText(msgs);

        boolean leader = system.contains("团队 Leader");

        // 1) 工具结果回传:按已执行的工具推进脚本
        if (last instanceof ToolResponseMessage toolResp) {
            List<String> seen = toolResp.getResponses().stream().map(r -> r.name()).toList();
            String joined = toolResp.getResponses().stream().map(r -> r.responseData() == null ? "" : r.responseData())
                    .reduce("", (a, b) -> a + b);

            if (leader) {
                if (seen.contains("teamTaskCreate")) {
                    return text("【进展】目标已拆解为任务并分配给成员(分配即唤醒)。成员完工后会自动唤醒我,届时向用户汇总。standing by。");
                }
                if (seen.contains("teamMembers")) {
                    String owner = firstMemberSn(joined);
                    if (owner == null) {
                        return text("团队还没有可派活的成员。请先在调度台「加入成员」,再投递目标。");
                    }
                    String goal = goalOf(userText);
                    String subject = abbreviate(goal, 40);
                    return tool(new AssistantMessage.ToolCall("mock-create", "function", "teamTaskCreate",
                            "{\"subject\":\"" + escape(subject) + "\",\"description\":\"" + escape(goal) + "\",\"ownerSn\":\"" + owner + "\"}"));
                }
                return text("standing by。");
            }

            if (seen.contains("teamSendMessage")) {
                return text("已完工并向 Leader 汇报。standing by。");
            }
            if (seen.contains("teamTaskUpdate")) {
                String taskId = taskIdOf(userText);
                return tool(
                        new AssistantMessage.ToolCall("mock-done", "function", "teamTaskUpdate",
                                "{\"taskId\":" + taskId + ",\"newStatus\":\"COMPLETED\"}"),
                        new AssistantMessage.ToolCall("mock-report", "function", "teamSendMessage",
                                "{\"toSn\":\"team-lead\",\"content\":\"任务 #" + taskId + " 已完成:已按验收标准给出结论与依据(Mock 演示输出)。\"}"));
            }
            return text("standing by。");
        }

        // 2) 起始步
        if (!userText.startsWith("【团队唤醒】")) {
            return text("收到。当前为演示模式(Mock 模型,可在「设置 → 模型」里开关)。把目标「投递到信箱」给我,即可看到拆解-分配-执行-汇报的完整链路。");
        }
        if (leader) {
            // 用户目标(payload from=user)才拆任务;成员汇报/空闲通知一律汇报收尾,
            // 否则"报告与空闲通知分两次唤醒"会把报告误判成新目标,造成无限拆任务
            if (userText.contains("\"from\":\"user\"")) {
                return tool(new AssistantMessage.ToolCall("mock-roster", "function", "teamMembers", "{}"));
            }
            return text("【团队进展汇报】收到成员完工汇报与空闲通知,任务板已更新(见右侧面板)。投递新目标可继续演示。standing by。");
        }
        Matcher m = TASK_ID.matcher(userText);
        if (!m.find()) {
            return text("收到消息,已阅。standing by。");
        }
        return tool(new AssistantMessage.ToolCall("mock-start", "function", "teamTaskUpdate",
                "{\"taskId\":" + m.group(1) + ",\"newStatus\":\"IN_PROGRESS\"}"));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.just(call(prompt));
    }

    // ---------- helpers ----------

    /** 历史里最近的用户消息(唤醒 prompt / 直接对话输入都从这里取) */
    private String lastUserText(List<Message> msgs) {
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i) instanceof UserMessage u) {
                return u.getText();
            }
        }
        return "";
    }

    private ChatResponse text(String s) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(s).build())));
    }

    private ChatResponse tool(AssistantMessage.ToolCall... calls) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content("").toolCalls(List.of(calls)).build())));
    }

    private String firstMemberSn(String teamMembersJson) {
        Matcher m = MEMBER_SN.matcher(teamMembersJson);
        while (m.find()) {
            if (!"team-lead".equals(m.group(1))) {
                return m.group(1);
            }
        }
        return null;
    }

    private String goalOf(String wakePrompt) {
        Matcher m = GOAL_LINE.matcher(wakePrompt);
        return m.find() ? m.group(1).trim() : "未命名目标";
    }

    private String taskIdOf(String wakePrompt) {
        Matcher m = TASK_ID.matcher(wakePrompt);
        return m.find() ? m.group(1) : "0";
    }

    private String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
