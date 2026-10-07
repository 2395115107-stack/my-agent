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
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 巡查编排器回归:对账(新增/累计/解除)、通知冷却窗、ACK 语义、自动催办只执行一次。 */
class PatrolServiceTest {

    private TeamMapper teamMapper;
    private TeamTaskMapper taskMapper;
    private PatrolFindingMapper findingMapper;
    private MailboxService mailboxService;
    private AgentRegistry registry;
    private Team team;

    /** 内存 finding 表:insert 回填进列表,查询返回快照,模拟跨巡次持久化。 */
    private final List<PatrolFinding> store = new ArrayList<>();

    private PatrolService service(PatrolCheck... checks) {
        return new PatrolService(teamMapper, taskMapper, findingMapper, mailboxService,
                mock(TeamEventPublisher.class), registry, new PatrolProperties(), List.of(checks));
    }

    /** 固定巡检项桩:按预设返回命中列表。 */
    private PatrolCheck stubCheck(String key, PatrolItem... items) {
        return new PatrolCheck() {
            @Override public String key() { return key; }
            @Override public String description() { return key; }
            @Override public List<PatrolItem> check(Team t) { return List.of(items); }
        };
    }

    @BeforeEach
    void setUp() {
        store.clear();
        teamMapper = mock(TeamMapper.class);
        taskMapper = mock(TeamTaskMapper.class);
        findingMapper = mock(PatrolFindingMapper.class);
        mailboxService = mock(MailboxService.class);
        registry = mock(AgentRegistry.class);

        team = new Team();
        team.setId(1L);
        team.setLeadSn("team-lead");
        team.setStatus("ACTIVE");
        when(teamMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(team));
        when(findingMapper.selectListByQuery(any(QueryWrapper.class)))
                .thenAnswer(inv -> new ArrayList<>(store));
        when(findingMapper.insert(any(PatrolFinding.class))).thenAnswer(inv -> {
            PatrolFinding f = inv.getArgument(0);
            f.setId((long) (store.size() + 1));
            store.add(f);
            return 1;
        });
    }

    @Test
    void newFindingInsertedAndLeaderNotified() {
        PatrolService svc = service(stubCheck("stalled_task",
                PatrolItem.warn("task#5", "停滞 12 分钟", "催办").withAction("nudge_owner")));

        List<PatrolFinding> touched = svc.patrolTeam(team, "test");

        assertEquals(1, touched.size());
        assertEquals(PatrolFinding.STATUS_OPEN, store.get(0).getStatus());
        assertEquals(1, store.get(0).getOccurrenceCount());
        assertEquals("stalled_task", store.get(0).getCheckKey());
        // 新发现不受冷却窗限制,立即通知 Leader
        verify(mailboxService).deliver(eq(1L), eq("team-lead"), eq(MailboxMessage.TYPE_MESSAGE), eq(5),
                contains("停滞"), any());
        assertNotNull(touched.get(0).getNotifiedAt());
    }

    @Test
    void occurrenceBumpsAndCooldownSuppressesNotify() {
        PatrolService svc = service(stubCheck("stalled_task",
                PatrolItem.warn("task#5", "停滞 12 分钟", "催办").withAction("nudge_owner")));
        svc.patrolTeam(team, "test");
        store.get(0).setNotifiedAt(LocalDateTime.now().minusSeconds(60));
        int notifies = 1;

        svc.patrolTeam(team, "test");

        assertEquals(2, store.get(0).getOccurrenceCount());
        assertEquals(PatrolFinding.STATUS_OPEN, store.get(0).getStatus());
        // 冷却窗(600s)内不重复通知;催办动作因 autoAction 已落库也不重复执行
        assertEquals(1, store.size());
        verify(mailboxService, times(notifies)).deliver(anyLong(), anyString(), anyString(), anyInt(), anyString(), any());
    }

    @Test
    void missingFindingResolvedOnNextRun() {
        PatrolService detecting = service(stubCheck("stalled_task",
                PatrolItem.warn("task#5", "停滞", "催办")));
        detecting.patrolTeam(team, "test");
        PatrolService quiet = service(stubCheck("stalled_task"));

        quiet.patrolTeam(team, "test");

        assertEquals(PatrolFinding.STATUS_RESOLVED, store.get(0).getStatus());
        assertNotNull(store.get(0).getResolvedAt());
        verify(mailboxService, times(1)).deliver(anyLong(), anyString(), anyString(), anyInt(), anyString(), any());
    }

    @Test
    void ackStaysQuietButCriticalEscalates() {
        PatrolService warn = service(stubCheck("stalled_task",
                PatrolItem.warn("task#5", "停滞", "催办")));
        warn.patrolTeam(team, "test");          // 新发现:立即通知(与本断言无关)
        store.get(0).setStatus(PatrolFinding.STATUS_ACK);
        clearInvocations(mailboxService);

        warn.patrolTeam(team, "test");
        assertEquals(PatrolFinding.STATUS_ACK, store.get(0).getStatus());
        verify(mailboxService, never()).deliver(anyLong(), anyString(), anyString(), anyInt(), anyString(), any());

        // 升级为 CRITICAL:重开 + 重新通知
        PatrolService critical = service(stubCheck("stalled_task",
                PatrolItem.critical("task#5", "owner 已暂停", "恢复槽位")));
        critical.patrolTeam(team, "test");
        assertEquals(PatrolFinding.STATUS_OPEN, store.get(0).getStatus());
        verify(mailboxService, times(1)).deliver(eq(1L), eq("team-lead"), eq(MailboxMessage.TYPE_MESSAGE), eq(5),
                contains("owner 已暂停"), any());
    }

    @Test
    void nudgeActionExecutesOnlyOnce() {
        TeamTask task = new TeamTask();
        task.setId(5L);
        task.setSubject("调研选型");
        task.setOwnerSn("analyst-01");
        task.setStatus(TeamTask.STATUS_IN_PROGRESS);
        when(taskMapper.selectOneById(5L)).thenReturn(task);
        when(registry.exists("analyst-01")).thenReturn(true);
        PatrolService svc = service(stubCheck("stalled_task",
                PatrolItem.warn("task#5", "停滞 12 分钟", "催办").withAction("nudge_owner")));

        svc.patrolTeam(team, "test");
        svc.patrolTeam(team, "test");
        svc.patrolTeam(team, "test");

        verify(mailboxService, times(1)).deliver(eq(1L), eq("analyst-01"),
                eq(MailboxMessage.TYPE_TASK_ASSIGN), eq(0), contains("巡查"), any());
        assertTrue(store.get(0).getAutoAction().startsWith("已自动催办"));
    }

    @Test
    void reopenedFindingReNudgesAfterResolve() {
        TeamTask task = new TeamTask();
        task.setId(5L);
        task.setSubject("调研选型");
        task.setOwnerSn("analyst-01");
        task.setStatus(TeamTask.STATUS_IN_PROGRESS);
        when(taskMapper.selectOneById(5L)).thenReturn(task);
        when(registry.exists("analyst-01")).thenReturn(true);
        PatrolService detecting = service(stubCheck("stalled_task",
                PatrolItem.warn("task#5", "停滞", "催办").withAction("nudge_owner")));
        detecting.patrolTeam(team, "test");
        service(stubCheck("stalled_task")).patrolTeam(team, "test");      // 解除
        assertEquals(PatrolFinding.STATUS_RESOLVED, store.get(0).getStatus());
        assertNotNull(store.get(0).getAutoAction());                       // 首轮催办已留痕

        detecting.patrolTeam(team, "test");                                // 复发 -> 重开
        assertEquals(PatrolFinding.STATUS_OPEN, store.get(0).getStatus());
        assertNull(store.get(0).getResolvedAt());
        assertEquals(2, store.get(0).getOccurrenceCount());
        assertEquals(1, store.size());
        // 复发重催:autoAction 已在重开时清空并重新执行,owner 收到第二次催办
        verify(mailboxService, times(2)).deliver(eq(1L), eq("analyst-01"),
                eq(MailboxMessage.TYPE_TASK_ASSIGN), eq(0), contains("巡查"), any());
        // 重开必须全量更新(ignoreNulls=false),否则 resolved_at=NULL 写不进库
        verify(findingMapper).update(store.get(0), false);
    }

    @Test
    void retentionSweepDeletesOnlyResolvedFindings() {
        PatrolService svc = service();
        when(findingMapper.deleteByQuery(any(QueryWrapper.class))).thenReturn(3);
        assertEquals(3, svc.sweepResolved(7));
        verify(findingMapper).deleteByQuery(any(QueryWrapper.class));
    }

    @Test
    void sameEntityFindingsGroupedIntoOneNotification() {
        PatrolService svc = service(
                stubCheck("paused_member", PatrolItem.warn("member:analyst-01", "槽位已暂停", "恢复槽位")),
                stubCheck("turn_failure", PatrolItem.critical("member:analyst-01", "连续 3 回合失败", "排查端点")),
                stubCheck("stalled_task", PatrolItem.critical("task#7", "owner 槽位已暂停", "恢复槽位")
                        .withEntity("member:analyst-01")));
        svc.patrolTeam(team, "test");

        // 同一实体的三条发现(含通过 entity 声明关联的停滞任务)合并为一封信箱告警
        org.mockito.ArgumentCaptor<String> contentCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mailboxService, times(1)).deliver(eq(1L), eq("team-lead"),
                eq(MailboxMessage.TYPE_MESSAGE), eq(5), contentCaptor.capture(), any());
        assertTrue(contentCaptor.getValue().contains("关联告警 ×3"), contentCaptor.getValue());
        assertTrue(contentCaptor.getValue().contains("paused_member"));
        assertTrue(contentCaptor.getValue().contains("turn_failure"));
        assertTrue(contentCaptor.getValue().contains("stalled_task"));
        // 三条发现的 notifiedAt 都落库(冷却窗按发现各自计)
        assertEquals(3, store.size());
        assertTrue(store.stream().allMatch(f -> f.getNotifiedAt() != null));
    }

    @Test
    void differentEntitiesNotifySeparately() {
        PatrolService svc = service(
                stubCheck("paused_member", PatrolItem.warn("member:analyst-01", "暂停", "恢复")),
                stubCheck("unassigned_task", PatrolItem.warn("task#9", "未分配", "派发")));
        svc.patrolTeam(team, "test");

        verify(mailboxService, times(2)).deliver(eq(1L), eq("team-lead"),
                eq(MailboxMessage.TYPE_MESSAGE), eq(5), anyString(), any());
    }

    @Test
    void listFindingsOpensFirstThenResolved() {
        PatrolFinding resolved = finding("unassigned_task", "task#9", PatrolFinding.STATUS_RESOLVED);
        resolved.setSeverity(PatrolFinding.SEVERITY_WARN);
        PatrolFinding warn = finding("stalled_task", "task#5", PatrolFinding.STATUS_OPEN);
        warn.setSeverity(PatrolFinding.SEVERITY_WARN);
        PatrolFinding critical = finding("mailbox_backlog", "member:x", PatrolFinding.STATUS_OPEN);
        critical.setSeverity(PatrolFinding.SEVERITY_CRITICAL);

        PatrolService svc = service();
        when(findingMapper.selectListByQuery(any(QueryWrapper.class)))
                .thenReturn(List.of(resolved, warn, critical));

        List<PatrolFinding> listed = svc.listFindings(1L);

        assertEquals(List.of("mailbox_backlog", "stalled_task", "unassigned_task"),
                listed.stream().map(PatrolFinding::getCheckKey).toList());
    }

    private PatrolFinding finding(String checkKey, String subject, String status) {
        PatrolFinding f = new PatrolFinding();
        f.setId(77L);
        f.setTeamId(1L);
        f.setCheckKey(checkKey);
        f.setSubject(subject);
        f.setStatus(status);
        f.setSeverity(PatrolFinding.SEVERITY_WARN);
        f.setOccurrenceCount(1);
        f.setFirstSeenAt(LocalDateTime.now());
        f.setLastSeenAt(LocalDateTime.now());
        return f;
    }
}
