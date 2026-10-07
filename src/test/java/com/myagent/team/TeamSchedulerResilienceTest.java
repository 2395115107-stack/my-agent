package com.myagent.team;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 可靠性模式回归:失败重投指数退避+抖动的边界、熔断探活冷却的指数曲线与封顶。 */
class TeamSchedulerResilienceTest {

    @Test
    void firstRetryHasNoBackoffDelay() {
        assertEquals(0, TeamScheduler.nextRetryDelayMs(1, 5_000, 120_000));
        assertEquals(0, TeamScheduler.nextRetryDelayMs(0, 5_000, 120_000));
    }

    @Test
    void retryBackoffGrowsExponentiallyWithinJitterBand() {
        // attempts=2 -> base(5s)±20%;attempts=3 -> 2*base±20%
        long d2 = TeamScheduler.nextRetryDelayMs(2, 5_000, 120_000);
        long d3 = TeamScheduler.nextRetryDelayMs(3, 5_000, 120_000);
        assertTrue(d2 >= 4_000 && d2 <= 6_000, "d2=" + d2);
        assertTrue(d3 >= 8_000 && d3 <= 12_000, "d3=" + d3);
        assertTrue(d3 > d2, "退避应随次数增长");
    }

    @Test
    void retryBackoffCappedAndNeverNegative() {
        for (int attempts = 4; attempts <= 12; attempts++) {
            long d = TeamScheduler.nextRetryDelayMs(attempts, 5_000, 120_000);
            assertTrue(d >= 0 && d <= 120_000, "attempts=" + attempts + " d=" + d);
        }
        // 大次数全部贴着 cap(抖动只向下不影响:cap 在抖动后再次收敛)
        long d = TeamScheduler.nextRetryDelayMs(30, 5_000, 120_000);
        assertTrue(d >= 96_000 && d <= 120_000, "d=" + d);
    }

    @Test
    void probeCooldownExponentialWithCap() {
        assertEquals(120, TeamScheduler.probeCooldownSeconds(1, 120, 1800));
        assertEquals(240, TeamScheduler.probeCooldownSeconds(2, 120, 1800));
        assertEquals(480, TeamScheduler.probeCooldownSeconds(3, 120, 1800));
        assertEquals(1800, TeamScheduler.probeCooldownSeconds(5, 120, 1800));
        assertEquals(1800, TeamScheduler.probeCooldownSeconds(20, 120, 1800));
        // 空值/非法值按首次暂停处理
        assertEquals(120, TeamScheduler.probeCooldownSeconds(null, 120, 1800));
        assertEquals(120, TeamScheduler.probeCooldownSeconds(0, 120, 1800));
    }
}
