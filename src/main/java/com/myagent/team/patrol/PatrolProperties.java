package com.myagent.team.patrol;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 巡查配置(myagent.patrol.*)。运行期只读启动值,阈值类字段可经 tbl_setting 演进(对齐治理参数做法)。
 */
@Data
@Component
@ConfigurationProperties(prefix = "myagent.patrol")
public class PatrolProperties {
    /** 巡查总开关 */
    private volatile boolean enabled = true;
    /** 定时巡查间隔(兜底;主路径是手动「立即巡查」与事件驱动刷新) */
    private volatile long intervalMs = 30_000;
    /** IN_PROGRESS 任务停滞阈值:owner 无进行中回合且任务超过该分钟数未更新 */
    private volatile int stalledMinutes = 10;
    /** 无主 PENDING 任务滞留阈值(分钟) */
    private volatile int unassignedMinutes = 30;
    /** 未读积压年龄阈值(分钟):最老未读超过该值且成员空闲 */
    private volatile int backlogAgeMinutes = 15;
    /** 未读积压数量阈值 */
    private volatile int backlogUnread = 30;
    /** 连续回合失败次数阈值(带 fail_reason 产出 CRITICAL) */
    private volatile int consecutiveFailures = 3;
    /** 同一发现重复通知 Leader 的冷却窗(秒) */
    private volatile int notifyCooldownSeconds = 600;
    /** 是否允许自动催办(对停滞任务 owner 重投任务通知) */
    private volatile boolean autoNudge = true;
    /** 熔断半开自动探活:暂停槽位到期自动转 ACTIVE 试一回合(失败回暂停、冷却翻倍) */
    private volatile boolean autoProbePaused = true;
    /** 探活冷却基数(秒):第 n 次连续暂停后等待 base*2^(n-1) */
    private volatile int probeBaseSeconds = 120;
    /** 探活冷却封顶(秒) */
    private volatile int probeCapSeconds = 1800;
    /** 唤醒时给 Leader 注入巡查简报(MAPE-K Knowledge) */
    private volatile boolean injectWake = true;
    /** 任务完成核验回看窗(分钟):只核验近窗内完成的任务 */
    private volatile int unverifiedLookbackMinutes = 120;
    /** 已解除(RESOLVED)发现的保留天数,超期由定时巡查清理 */
    private volatile int retentionDays = 7;
}
