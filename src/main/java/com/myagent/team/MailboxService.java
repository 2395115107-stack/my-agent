package com.myagent.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.team.entity.MailboxMessage;
import com.myagent.team.mapper.MailboxMessageMapper;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

import static com.myagent.team.entity.table.MailboxMessageTableDef.MAILBOX_MESSAGE;

/**
 * 信箱:分配怎么可靠送达。逐条对齐 AionUi mailbox.rs 的可靠性语义:
 * - FIFO:读取按 priority DESC, id ASC(中断替换指令 priority=10 必然最先被注入)
 * - 已读确认:只有 turn 成功完成才标已读(TurnLifecycleService),失败保留重投
 * - 唤醒:写入即发布事件(主路径)+ 调度对账循环(兜底)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailboxService {

    private final MailboxMessageMapper mailboxMapper;
    private final ApplicationEventPublisher events;
    private final ObjectMapper objectMapper;

    /** 信箱写入事件:TeamScheduler 监听后立即调度该成员(事件驱动主路径)。 */
    public record MailboxDelivered(Long teamId, String agentSn) {}

    public MailboxMessage deliver(Long teamId, String agentSn, String msgType, int priority,
                                  String content, Object payload) {
        MailboxMessage msg = new MailboxMessage();
        msg.setTeamId(teamId);
        msg.setAgentSn(agentSn);
        msg.setMsgType(msgType);
        msg.setPriority(priority);
        msg.setContent(content);
        msg.setPayload(toJson(payload));
        msg.setIsRead(false);
        msg.setCreatedAt(LocalDateTime.now());
        mailboxMapper.insert(msg);
        events.publishEvent(new MailboxDelivered(teamId, agentSn));
        return msg;
    }

    /** 取最老的最多 limit 条未读(高优先级在前)。不标已读——已读语义归 turn 生命周期管。 */
    public List<MailboxMessage> unread(String agentSn, int limit) {
        return mailboxMapper.selectListByQuery(QueryWrapper.create()
                .where(MAILBOX_MESSAGE.AGENT_SN.eq(agentSn))
                .and(MAILBOX_MESSAGE.IS_READ.eq(false))
                .orderBy(MAILBOX_MESSAGE.PRIORITY.desc(), MAILBOX_MESSAGE.ID.asc())
                .limit(limit));
    }

    public long unreadCount(String agentSn) {
        return mailboxMapper.selectCountByQuery(QueryWrapper.create()
                .where(MAILBOX_MESSAGE.AGENT_SN.eq(agentSn))
                .and(MAILBOX_MESSAGE.IS_READ.eq(false)));
    }

    /** 仅在成员 turn 成功完成后由 TurnLifecycleService 调用(精确按注入的消息 ID)。 */
    public void markRead(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        MailboxMessage patch = new MailboxMessage();
        patch.setIsRead(true);
        patch.setReadAt(LocalDateTime.now());
        mailboxMapper.updateByQuery(patch, QueryWrapper.create()
                .where(MAILBOX_MESSAGE.ID.in(ids)));
    }

    private String toJson(Object o) {
        try {
            return o == null ? null : objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            log.warn("payload serialize failed", e);
            return null;
        }
    }
}
