package com.myagent.team.patrol.check;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.engine.AgentRegistry;
import com.myagent.team.MailboxService;
import com.myagent.team.TeamEventPublisher;
import com.myagent.team.TurnLifecycleService;
import com.myagent.team.entity.MailboxMessage;
import com.myagent.team.entity.Team;
import com.myagent.team.entity.TeamMember;
import com.myagent.team.entity.TeamTask;
import com.myagent.team.entity.TeamTurn;
import com.myagent.team.mapper.MailboxMessageMapper;
import com.myagent.team.mapper.TeamMapper;
import com.myagent.team.mapper.TeamMemberMapper;
import com.myagent.team.mapper.TeamTaskMapper;
import com.myagent.team.mapper.TeamTurnMapper;
import com.myagent.team.patrol.PatrolCheck.PatrolItem;
import com.myagent.team.patrol.PatrolFinding;
import com.myagent.team.patrol.PatrolProperties;
import com.myagent.team.TeamProperties;
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 内置巡检项回归:停滞任务/无主任务/信箱积压/暂停槽位/连续回合失败的命中与豁免边界。 */
class PatrolChecksTest {

    private static final Team TEAM = team();

    private TeamTaskMapper taskMapper;
    private TeamMemberMapper memberMapper;
    private TeamTurnMapper turnMapper;
    private MailboxMessageMapper mailboxMapper;
    private AgentRegistry registry;
    private PatrolProperties props;
    private TurnLifecycleService lifecycle;

    @BeforeEach
    void setUp() {
        taskMapper = mock(TeamTaskMapper.class);
        memberMapper = mock(TeamMemberMapper.class);
        turnMapper = mock(TeamTurnMapper.class);
        mailboxMapper = mock(MailboxMessageMapper.class);
        registry = mock(AgentRegistry.class);
        props = new PatrolProperties();
        lifecycle = new TurnLifecycleService(turnMapper, memberMapper, mock(TeamMapper.class),
                mock(MailboxService.class), mock(TeamEventPublisher.class),
                new TeamProperties(), new ObjectMapper());
    }

    // ---------- stalled_task ----------

    @Test
    void stalledTaskFlagsIdleOwnerWithNudge() {
        inProgressTask("analyst-01", LocalDateTime.now().minusMinutes(20));
        activeMember("analyst-01");
        when(turnMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of());

        List<PatrolItem> items = new StalledTaskCheck(taskMapper, memberMapper, lifecycle, props).check(TEAM);

        assertEquals(1, items.size());
        assertEquals(PatrolFinding.SEVERITY_WARN, items.get(0).severity());
        assertEquals("task#5", items.get(0).subject());
        assertEquals(StalledTaskCheck.ACTION_NUDGE_OWNER, items.get(0).action());
    }

    @Test
    void stalledTaskSkipsBusyOwnerAndRecentTasks() {
        inProgressTask("analyst-01", LocalDateTime.now().minusMinutes(20));
        activeMember("analyst-01");
        // owner 正在跑一个未过期的回合 -> 不算停滞
        TeamTurn running = new TeamTurn();
        running.setStatus(TeamTurn.STATUS_RUNNING);
        running.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(5));
        when(turnMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(running));
        assertTrue(new StalledTaskCheck(taskMapper, memberMapper, lifecycle, props).check(TEAM).isEmpty());

        // 任务 5 分钟前刚更新(< 阈值 10 分钟)-> 不算停滞
        inProgressTask("analyst-01", LocalDateTime.now().minusMinutes(5));
        assertTrue(new StalledTaskCheck(taskMapper, memberMapper, lifecycle, props).check(TEAM).isEmpty());
    }

    @Test
    void stalledTaskEscalatesWhenOwnerPaused() {
        inProgressTask("analyst-01", LocalDateTime.now().minusMinutes(20));
        TeamMember paused = member("analyst-01");
        paused.setStatus(TeamMember.STATUS_PAUSED);
        when(memberMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(paused));
        when(turnMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of());

        List<PatrolItem> items = new StalledTaskCheck(taskMapper, memberMapper, lifecycle, props).check(TEAM);

        assertEquals(1, items.size());
        assertEquals(PatrolFinding.SEVERITY_CRITICAL, items.get(0).severity());
    }

    // ---------- unassigned_task ----------

    @Test
    void unassignedPendingTaskFlaggedOnlyWhenStale() {
        TeamTask stale = pendingTask(null, LocalDateTime.now().minusMinutes(40));
        TeamTask fresh = pendingTask(null, LocalDateTime.now().minusMinutes(5));
        TeamTask owned = pendingTask("analyst-01", LocalDateTime.now().minusMinutes(40));
        when(taskMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(stale, fresh, owned));

        List<PatrolItem> items = new UnassignedTaskCheck(taskMapper, props).check(TEAM);

        assertEquals(1, items.size());
        assertEquals("task#" + stale.getId(), items.get(0).subject());
    }

    // ---------- mailbox_backlog ----------

    @Test
    void backlogFlagsOldUnreadAndGhosts() {
        activeMember("analyst-01");
        MailboxMessage old = unread(LocalDateTime.now().minusMinutes(30));
        when(mailboxMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(old));
        when(mailboxMapper.selectCountByQuery(any(QueryWrapper.class))).thenReturn(2L);
        when(turnMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of());
        when(registry.exists("analyst-01")).thenReturn(true);

        List<PatrolItem> items = new MailboxBacklogCheck(memberMapper, mailboxMapper, turnMapper, registry, props)
                .check(TEAM);
        assertEquals(1, items.size());
        assertEquals(PatrolFinding.SEVERITY_WARN, items.get(0).severity());

        // 幽灵成员:注册表缺失 -> CRITICAL
        when(registry.exists("analyst-01")).thenReturn(false);
        items = new MailboxBacklogCheck(memberMapper, mailboxMapper, turnMapper, registry, props).check(TEAM);
        assertEquals(1, items.size());
        assertEquals(PatrolFinding.SEVERITY_CRITICAL, items.get(0).severity());

        // 无未读 -> 无发现
        when(mailboxMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of());
        items = new MailboxBacklogCheck(memberMapper, mailboxMapper, turnMapper, registry, props).check(TEAM);
        assertTrue(items.isEmpty());
    }

    // ---------- paused_member / turn_failure ----------

    @Test
    void pausedMemberFlagged() {
        TeamMember paused = member("analyst-01");
        paused.setStatus(TeamMember.STATUS_PAUSED);
        when(memberMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(paused));

        List<PatrolItem> items = new PausedMemberCheck(memberMapper).check(TEAM);
        assertEquals(1, items.size());
        assertEquals("member:analyst-01", items.get(0).subject());
    }

    @Test
    void consecutiveTurnFailuresFlaggedWithReason() {
        activeMember("analyst-01");
        when(turnMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(
                failedTurn("stream error"), failedTurn("lease expired"), failedTurn("stream error")));

        List<PatrolItem> items = new TurnFailureCheck(memberMapper, turnMapper, props).check(TEAM);

        assertEquals(1, items.size());
        assertEquals(PatrolFinding.SEVERITY_CRITICAL, items.get(0).severity());
        assertTrue(items.get(0).detail().contains("stream error"), items.get(0).detail());

        // 最近一个回合成功 -> 连续性被打破 -> 无发现
        TeamTurn done = new TeamTurn();
        done.setStatus(TeamTurn.STATUS_DONE);
        when(turnMapper.selectListByQuery(any(QueryWrapper.class)))
                .thenReturn(List.of(done, failedTurn("x"), failedTurn("x")));
        assertTrue(new TurnFailureCheck(memberMapper, turnMapper, props).check(TEAM).isEmpty());
    }

    // ---------- unverified_completion(MAST 任务验证) ----------

    @Test
    void completedTaskWithoutReportFlagged() {
        MailboxMessageMapper mailboxMapper2 = org.mockito.Mockito.mock(MailboxMessageMapper.class);
        TeamTask done = new TeamTask();
        done.setId(21L);
        done.setTeamId(1L);
        done.setSubject("已完成的任务");
        done.setOwnerSn("analyst-01");
        done.setStatus(TeamTask.STATUS_COMPLETED);
        done.setUpdatedAt(LocalDateTime.now().minusMinutes(5));
        when(taskMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(done));
        when(mailboxMapper2.selectCountByQuery(any(QueryWrapper.class))).thenReturn(0L);

        List<PatrolItem> items = new UnverifiedCompletionCheck(taskMapper, mailboxMapper2, props).check(TEAM);

        assertEquals(1, items.size());
        assertEquals("task#21", items.get(0).subject());
        assertEquals(PatrolFinding.SEVERITY_WARN, items.get(0).severity());

        // 有汇报 -> 不命中;owner 是 Leader 自己 -> 跳过
        when(mailboxMapper2.selectCountByQuery(any(QueryWrapper.class))).thenReturn(1L);
        assertTrue(new UnverifiedCompletionCheck(taskMapper, mailboxMapper2, props).check(TEAM).isEmpty());
        done.setOwnerSn("team-lead");
        when(mailboxMapper2.selectCountByQuery(any(QueryWrapper.class))).thenReturn(0L);
        assertTrue(new UnverifiedCompletionCheck(taskMapper, mailboxMapper2, props).check(TEAM).isEmpty());
    }

    // ---------- fixtures ----------

    private static Team team() {
        Team t = new Team();
        t.setId(1L);
        t.setLeadSn("team-lead");
        t.setStatus("ACTIVE");
        return t;
    }

    private void inProgressTask(String owner, LocalDateTime updatedAt) {
        TeamTask task = new TeamTask();
        task.setId(5L);
        task.setTeamId(1L);
        task.setSubject("调研选型");
        task.setOwnerSn(owner);
        task.setStatus(TeamTask.STATUS_IN_PROGRESS);
        task.setUpdatedAt(updatedAt);
        when(taskMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(task));
    }

    private TeamTask pendingTask(String owner, LocalDateTime createdAt) {
        TeamTask task = new TeamTask();
        task.setId(9L);
        task.setTeamId(1L);
        task.setSubject("待分配任务");
        task.setOwnerSn(owner);
        task.setStatus(TeamTask.STATUS_PENDING);
        task.setCreatedAt(createdAt);
        return task;
    }

    private TeamMember member(String sn) {
        TeamMember m = new TeamMember();
        m.setId(11L);
        m.setTeamId(1L);
        m.setAgentSn(sn);
        m.setStatus(TeamMember.STATUS_ACTIVE);
        return m;
    }

    private void activeMember(String sn) {
        when(memberMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(member(sn)));
    }

    private MailboxMessage unread(LocalDateTime createdAt) {
        MailboxMessage msg = new MailboxMessage();
        msg.setId(1L);
        msg.setTeamId(1L);
        msg.setAgentSn("analyst-01");
        msg.setIsRead(false);
        msg.setCreatedAt(createdAt);
        return msg;
    }

    private TeamTurn failedTurn(String reason) {
        TeamTurn turn = new TeamTurn();
        turn.setId(1L);
        turn.setTeamId(1L);
        turn.setAgentSn("analyst-01");
        turn.setStatus(TeamTurn.STATUS_FAILED);
        turn.setFailReason(reason);
        return turn;
    }
}
