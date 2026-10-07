package com.myagent.team.patrol;

import com.myagent.engine.AgentRegistry;
import com.myagent.team.MailboxService;
import com.myagent.team.TeamEventPublisher;
import com.myagent.team.entity.MailboxMessage;
import com.myagent.team.entity.Team;
import com.myagent.team.entity.TeamTask;
import com.myagent.team.mapper.PatrolFindingMapper;
import com.myagent.team.mapper.TeamMapper;
import com.myagent.team.mapper.TeamTaskMapper;
import com.myagent.team.patrol.PatrolCheck.PatrolItem;
import com.myagent.team.patrol.check.StalledTaskCheck;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.myagent.team.entity.table.TeamTableDef.TEAM;
import static com.myagent.team.patrol.table.PatrolFindingTableDef.PATROL_FINDING;

/**
 * 巡查编排器:定时 + 手动触发,对全部 ACTIVE 团队执行「巡检 -> 对账 -> 处置」。
 *
 * - 巡检项是 SPI(PatrolCheck),只发现问题;本类统一做落库对账、自动处置与 Leader 通知,
 *   保证同一异常跨巡次的幂等(去重键 = checkKey|subject);
 * - 对账语义:新命中 -> OPEN;持续命中 -> occurrence/last_seen 累加;消失 -> RESOLVED;RESOLVED 后复现 -> 重开;
 * - 自动处置按 action 声明执行(如 nudge_owner),首次命中执行一次,不随巡次重复;
 * - Leader 通知走冷却窗(notify-cooldown-seconds),ACK 过的发现不再通知(CRITICAL 升级会重开)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PatrolService {

    private final TeamMapper teamMapper;
    private final TeamTaskMapper taskMapper;
    private final PatrolFindingMapper findingMapper;
    private final MailboxService mailboxService;
    private final TeamEventPublisher events;
    private final AgentRegistry registry;
    private final PatrolProperties props;
    private final List<PatrolCheck> checks;

    @Scheduled(fixedDelayString = "${myagent.patrol.interval-ms:30000}")
    public void scheduledPatrol() {
        if (!props.isEnabled()) {
            return;
        }
        try {
            sweepResolved(props.getRetentionDays());
        } catch (Exception e) {
            log.warn("[patrol] resolved findings sweep failed", e);
        }
        try {
            patrolAll("scheduled");
        } catch (Exception e) {
            log.error("[patrol] scheduled patrol failed", e);
        }
    }

    /** 保留策略:RESOLVED 超过保留天数的发现由定时巡查顺手清理,防止 finding 表无限增长。 */
    public int sweepResolved(int retentionDays) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(Math.max(1, retentionDays));
        return findingMapper.deleteByQuery(QueryWrapper.create()
                .where(PATROL_FINDING.STATUS.eq(PatrolFinding.STATUS_RESOLVED))
                .and(PATROL_FINDING.RESOLVED_AT.le(cutoff)));
    }

    /** 对全部 ACTIVE 团队巡查一轮,返回 teamId -> 本轮命中的发现。 */
    public Map<Long, List<PatrolFinding>> patrolAll(String trigger) {
        Map<Long, List<PatrolFinding>> result = new LinkedHashMap<>();
        List<Team> teams = teamMapper.selectListByQuery(QueryWrapper.create()
                .where(TEAM.STATUS.eq("ACTIVE")));
        for (Team team : teams) {
            try {
                result.put(team.getId(), patrolTeam(team, trigger));
            } catch (Exception e) {
                log.error("[patrol] team {} patrol failed", team.getId(), e);
            }
        }
        return result;
    }

    /** 单团队巡查:跑全部巡检项,对账落库,执行自动处置与通知。返回本轮命中的发现(含更新)。
     *  串行化:定时轮与手动轮共用一把锁 —— 对账基于「查询快照」,并发两轮会建出同键重复行。 */
    public synchronized List<PatrolFinding> patrolTeam(Team team, String trigger) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, PatrolItem> detected = new LinkedHashMap<>();
        for (PatrolCheck check : checks) {
            try {
                for (PatrolItem item : check.check(team)) {
                    detected.put(dedupeKey(check.key(), item.subject()), item);
                }
            } catch (Exception e) {
                log.error("[patrol] check {} on team {} failed", check.key(), team.getId(), e);
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("[patrol] team#{} ({}) checks={} detected={}",
                    team.getId(), trigger, checks.size(), detected.size());
        }

        Map<String, PatrolFinding> existing = new LinkedHashMap<>();
        for (PatrolFinding f : findingMapper.selectListByQuery(QueryWrapper.create()
                .where(PATROL_FINDING.TEAM_ID.eq(team.getId())))) {
            existing.put(dedupeKey(f.getCheckKey(), f.getSubject()), f);
        }

        List<PatrolFinding> touched = new ArrayList<>();
        List<NotifyIntent> notifyQueue = new ArrayList<>();
        for (Map.Entry<String, PatrolItem> entry : detected.entrySet()) {
            PatrolItem item = entry.getValue();
            PatrolFinding f = existing.remove(entry.getKey());
            if (f == null) {
                f = new PatrolFinding();
                f.setTeamId(team.getId());
                f.setCheckKey(entry.getKey().split("\\|", 2)[0]);
                f.setSubject(item.subject());
                f.setStatus(PatrolFinding.STATUS_OPEN);
                f.setOccurrenceCount(1);
                f.setFirstSeenAt(now);
                f.setLastSeenAt(now);
                applyItem(f, item);
                findingMapper.insert(f);
                events.publish("patrol_finding", team.getId(),
                        "new:" + f.getCheckKey() + ":" + f.getSubject());
            } else {
                boolean wasAck = PatrolFinding.STATUS_ACK.equals(f.getStatus());
                boolean wasResolved = PatrolFinding.STATUS_RESOLVED.equals(f.getStatus());
                boolean escalateToCritical = wasAck
                        && PatrolFinding.SEVERITY_CRITICAL.equals(item.severity());
                // ACK 语义:用户已认领,持续命中保持 ACK;仅 CRITICAL 升级时重开重警
                f.setStatus(wasAck && !escalateToCritical ? PatrolFinding.STATUS_ACK : PatrolFinding.STATUS_OPEN);
                f.setOccurrenceCount((f.getOccurrenceCount() == null ? 1 : f.getOccurrenceCount() + 1));
                f.setLastSeenAt(now);
                if (wasResolved) {
                    f.setResolvedAt(null);
                    // 复发重催:RESOLVED 后再次命中发现清除 autoAction 留痕,允许再次自动处置
                    f.setAutoAction(null);
                }
                if (escalateToCritical) {
                    f.setNotifiedAt(null);
                }
                applyItem(f, item);
                // ignoreNulls=false:重开路径要真正把 resolved_at/auto_action 写回 NULL
                findingMapper.update(f, false);
                if (escalateToCritical) {
                    events.publish("patrol_finding", team.getId(),
                            "escalated:" + f.getCheckKey() + ":" + f.getSubject());
                }
            }
            executeAction(team, item, f, now);
            if (notifyEligible(item, f)) {
                notifyQueue.add(new NotifyIntent(f, item));
            }
            touched.add(f);
        }
        notifyLeadGrouped(team, notifyQueue, now);
        // 本轮未命中的未解决发现 -> RESOLVED(RESOLVED 行不再重写,避免刷 resolved_at)
        for (PatrolFinding f : existing.values()) {
            if (PatrolFinding.STATUS_RESOLVED.equals(f.getStatus())) {
                continue;
            }
            f.setStatus(PatrolFinding.STATUS_RESOLVED);
            f.setResolvedAt(now);
            f.setLastSeenAt(now);
            findingMapper.update(f);
            events.publish("patrol_finding", team.getId(),
                    "resolved:" + f.getCheckKey() + ":" + f.getSubject());
        }
        return touched;
    }

    /** 巡查发现列表:未解决在前(严重度降序、最近发现优先),已解决按解决时间倒序,总量截断。 */
    public List<PatrolFinding> listFindings(Long teamId) {
        List<PatrolFinding> all = findingMapper.selectListByQuery(QueryWrapper.create()
                .where(PATROL_FINDING.TEAM_ID.eq(teamId))
                .limit(500));
        Comparator<PatrolFinding> bySeverity = Comparator
                .comparingInt((PatrolFinding f) -> switch (f.getSeverity() == null ? "" : f.getSeverity()) {
                    case PatrolFinding.SEVERITY_CRITICAL -> 0;
                    case PatrolFinding.SEVERITY_WARN -> 1;
                    default -> 2;
                });
        List<PatrolFinding> open = new ArrayList<>();
        List<PatrolFinding> closed = new ArrayList<>();
        for (PatrolFinding f : all) {
            (PatrolFinding.STATUS_RESOLVED.equals(f.getStatus()) ? closed : open).add(f);
        }
        open.sort(bySeverity.thenComparing(f -> f.getLastSeenAt(), Comparator.nullsLast(Comparator.reverseOrder())));
        closed.sort(Comparator.comparing(f -> f.getResolvedAt(), Comparator.nullsLast(Comparator.reverseOrder())));
        List<PatrolFinding> result = new ArrayList<>(open);
        result.addAll(closed);
        return result;
    }

    public PatrolFinding ack(Long findingId) {
        PatrolFinding f = findingMapper.selectOneById(findingId);
        if (f == null) {
            throw new IllegalArgumentException("finding not found: " + findingId);
        }
        if (!PatrolFinding.STATUS_RESOLVED.equals(f.getStatus())) {
            f.setStatus(PatrolFinding.STATUS_ACK);
            findingMapper.update(f);
        }
        return f;
    }

    private void applyItem(PatrolFinding f, PatrolItem item) {
        f.setSeverity(item.severity());
        f.setDetail(item.detail());
        f.setSuggestion(item.suggestion());
    }

    /** 自动处置:首次命中时执行一次(autoAction 落库后不再重复)。 */
    private void executeAction(Team team, PatrolItem item, PatrolFinding f, LocalDateTime now) {
        if (item.action() == null || (f.getAutoAction() != null && !f.getAutoAction().isBlank())) {
            return;
        }
        switch (item.action()) {
            case StalledTaskCheck.ACTION_NUDGE_OWNER -> {
                if (!props.isAutoNudge()) {
                    return;
                }
                TeamTask task = taskFromSubject(f.getSubject());
                if (task == null || task.getOwnerSn() == null || task.getOwnerSn().isBlank()
                        || !TeamTask.STATUS_IN_PROGRESS.equals(task.getStatus())
                        || !registry.exists(task.getOwnerSn())) {
                    return;
                }
                mailboxService.deliver(team.getId(), task.getOwnerSn(), MailboxMessage.TYPE_TASK_ASSIGN, 0,
                        "【巡查】任务 #" + task.getId() + "「" + task.getSubject()
                                + "」已停滞,请继续推进;若实际已完成,请用 team_task_update 更新状态",
                        Map.of("taskId", task.getId(), "patrol", true));
                f.setAutoAction("已自动催办 owner " + task.getOwnerSn() + "(@" + now + ")");
                findingMapper.update(f);
                events.publish("patrol_finding", team.getId(),
                        "action:" + f.getCheckKey() + ":" + f.getSubject());
            }
            default -> log.debug("[patrol] unknown action {} ignored", item.action());
        }
    }

    /** 通知资格:WARN 及以上、非 ACK、冷却窗之外。 */
    private boolean notifyEligible(PatrolItem item, PatrolFinding f) {
        if (PatrolFinding.SEVERITY_INFO.equals(item.severity())
                || PatrolFinding.STATUS_ACK.equals(f.getStatus())) {
            return false;
        }
        return f.getNotifiedAt() == null
                || !f.getNotifiedAt().isAfter(LocalDateTime.now().minusSeconds(props.getNotifyCooldownSeconds()));
    }

    private record NotifyIntent(PatrolFinding finding, PatrolItem item) {}

    /**
     * 同实体告警关联聚合(AIOps correlation):同一 member/task 的多条发现(如模型故障同时触发
     * turn_failure + paused_member + mailbox_backlog + stalled_task)合并为一封信箱告警,
     * 避免 Leader 被同一根因的消息风暴刷屏。
     */
    private void notifyLeadGrouped(Team team, List<NotifyIntent> queue, LocalDateTime now) {
        Map<String, List<NotifyIntent>> byEntity = new LinkedHashMap<>();
        for (NotifyIntent intent : queue) {
            byEntity.computeIfAbsent(entityKeyOf(intent), k -> new ArrayList<>()).add(intent);
        }
        for (List<NotifyIntent> group : byEntity.values()) {
            PatrolFinding first = group.get(0).finding();
            String content;
            if (group.size() == 1) {
                NotifyIntent intent = group.get(0);
                content = "【巡查/" + intent.item().severity() + "】" + intent.finding().getDetail()
                        + "。建议:" + intent.finding().getSuggestion();
            } else {
                StringBuilder sb = new StringBuilder("【巡查/关联告警 ×").append(group.size()).append("】实体 ")
                        .append(entityKeyOf(group.get(0)))
                        .append(" 命中多项巡检(可能同根因):\n");
                for (NotifyIntent intent : group) {
                    sb.append("- [").append(intent.item().severity()).append("|")
                      .append(intent.finding().getCheckKey()).append("] ")
                      .append(intent.finding().getDetail())
                      .append("。建议:").append(intent.finding().getSuggestion()).append("\n");
                }
                content = sb.toString();
            }
            Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("patrol", true);
            payload.put("entities", first.getSubject());
            payload.put("checkKeys", group.stream().map(i -> i.finding().getCheckKey()).toList());
            mailboxService.deliver(team.getId(), team.getLeadSn(), MailboxMessage.TYPE_MESSAGE, 5, content, payload);
            for (NotifyIntent intent : group) {
                intent.finding().setNotifiedAt(now);
                findingMapper.update(intent.finding());
            }
        }
    }

    /** 通知聚合键:优先巡检项声明的关联实体(停滞任务关联到 owner),与 subject 推导共用同一归一规则。 */
    private String entityKeyOf(NotifyIntent intent) {
        return subjectEntityKey(intent.item().entity() != null && !intent.item().entity().isBlank()
                ? intent.item().entity()
                : intent.finding().getSubject());
    }

    /** member:analyst-01 / task#12 -> analyst-01 / 12;其他格式整串聚合。 */
    static String subjectEntityKey(String subject) {
        if (subject == null) {
            return "";
        }
        int idx = subject.indexOf(':');
        if (idx >= 0) {
            return subject.substring(idx + 1);
        }
        idx = subject.indexOf('#');
        return idx >= 0 ? subject.substring(idx + 1) : subject;
    }

    private TeamTask taskFromSubject(String subject) {
        try {
            long id = Long.parseLong(subject.replaceFirst("^task#", ""));
            return taskMapper.selectOneById(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String dedupeKey(String checkKey, String subject) {
        return checkKey + "|" + subject;
    }
}
