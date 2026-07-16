package com.aiconnecting.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * ChannelHealthTracker 熔断器单元测试（内存回退模式，未注入 RedisTemplate）
 */
class ChannelHealthTrackerTest {

    private ChannelHealthTracker tracker;
    private static final AtomicLong CHANNEL_ID_SEQ = new AtomicLong(1000);

    @BeforeEach
    void setUp() {
        tracker = new ChannelHealthTracker();
        tracker.setChannelService(mock(ChannelService.class));
    }

    /** 每个测试使用独立渠道 ID，避免用例间状态互相污染 */
    private Long newChannelId() {
        return CHANNEL_ID_SEQ.incrementAndGet();
    }

    private void awaitAsync() {
        // healthExecutor 是单线程池，任务基本立即执行完毕，短暂等待即可
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void initialStateIsClosedAndNotBlocked() {
        Long id = newChannelId();
        assertEquals(ChannelHealthTracker.CircuitState.CLOSED, tracker.getEffectiveState(id));
        assertFalse(tracker.isBlocked(id));
    }

    @Test
    void clientErrorDoesNotAffectHealth() {
        Long id = newChannelId();
        for (int i = 0; i < 20; i++) {
            tracker.recordFailure(id, ChannelHealthTracker.ErrorCategory.CLIENT_ERROR, "bad request");
        }
        awaitAsync();
        assertEquals(ChannelHealthTracker.CircuitState.CLOSED, tracker.getEffectiveState(id));
        assertFalse(tracker.isBlocked(id));
    }

    @Test
    void rateLimitCausesCooldownButNotCircuitOpen() {
        Long id = newChannelId();
        tracker.recordFailure(id, ChannelHealthTracker.ErrorCategory.RATE_LIMIT, "429");
        awaitAsync();
        // 冷却期间 isBlocked() 应为 true（跳过该渠道），但熔断器本身仍是 CLOSED
        assertTrue(tracker.isBlocked(id));
        assertEquals(ChannelHealthTracker.CircuitState.CLOSED, tracker.getEffectiveState(id));
    }

    @Test
    void authErrorImmediatelyOpensCircuit() {
        Long id = newChannelId();
        tracker.recordFailure(id, ChannelHealthTracker.ErrorCategory.AUTH_ERROR, "401");
        awaitAsync();
        assertEquals(ChannelHealthTracker.CircuitState.OPEN, tracker.getEffectiveState(id));
        assertTrue(tracker.isBlocked(id));
    }

    @Test
    void tripsCircuitWhenErrorRateAndVolumeThresholdReached() {
        Long id = newChannelId();
        // 10 次失败，错误率 100% >= 50%，总请求数 10 >= 阈值 10 -> 应该熔断
        for (int i = 0; i < 10; i++) {
            tracker.recordFailure(id, ChannelHealthTracker.ErrorCategory.SERVER_ERROR, "500");
        }
        awaitAsync();
        assertEquals(ChannelHealthTracker.CircuitState.OPEN, tracker.getEffectiveState(id));
        assertTrue(tracker.isBlocked(id));
    }

    @Test
    void doesNotTripBelowMinimumRequestVolume() {
        Long id = newChannelId();
        // 只有 5 次失败，未达到最小请求数（10），不应熔断
        for (int i = 0; i < 5; i++) {
            tracker.recordFailure(id, ChannelHealthTracker.ErrorCategory.TIMEOUT, "timeout");
        }
        awaitAsync();
        assertEquals(ChannelHealthTracker.CircuitState.CLOSED, tracker.getEffectiveState(id));
        assertFalse(tracker.isBlocked(id));
    }

    @Test
    void doesNotTripWhenErrorRateBelowThreshold() {
        Long id = newChannelId();
        // 10 次成功 + 4 次失败 = 14 次请求，错误率 ~28.6% < 50%，不应熔断
        for (int i = 0; i < 10; i++) {
            tracker.recordSuccess(id);
        }
        for (int i = 0; i < 4; i++) {
            tracker.recordFailure(id, ChannelHealthTracker.ErrorCategory.CONNECTION_ERROR, "conn reset");
        }
        awaitAsync();
        assertEquals(ChannelHealthTracker.CircuitState.CLOSED, tracker.getEffectiveState(id));
    }

    @Test
    void halfOpenProbePermitNotAvailableWhileFullyOpen() {
        Long id = newChannelId();
        tracker.recordFailure(id, ChannelHealthTracker.ErrorCategory.AUTH_ERROR, "401");
        awaitAsync();
        assertEquals(ChannelHealthTracker.CircuitState.OPEN, tracker.getEffectiveState(id));
        // 仍在 OPEN 阻塞期内，不是 HALF_OPEN，不应发放探测许可
        assertFalse(tracker.tryAcquireHalfOpenProbe(id));
    }

    @Test
    void unblockChannelForcesCloseAndResetsState() {
        Long id = newChannelId();
        tracker.recordFailure(id, ChannelHealthTracker.ErrorCategory.AUTH_ERROR, "401");
        awaitAsync();
        assertTrue(tracker.isBlocked(id));

        tracker.unblockChannel(id);
        assertEquals(ChannelHealthTracker.CircuitState.CLOSED, tracker.getEffectiveState(id));
        assertFalse(tracker.isBlocked(id));
        assertFalse(tracker.tryAcquireHalfOpenProbe(id));
    }

    @Test
    void isOpenTooLongIsFalseRightAfterTripping() {
        Long id = newChannelId();
        tracker.recordFailure(id, ChannelHealthTracker.ErrorCategory.AUTH_ERROR, "401");
        awaitAsync();
        assertFalse(tracker.isOpenTooLong(id));
    }

    @Test
    void getBlockedChannelIdsIncludesRateLimitAndCircuitOpenButOpenIdsIsCircuitOnly() {
        Long rateLimited = newChannelId();
        Long circuitOpen = newChannelId();
        tracker.recordFailure(rateLimited, ChannelHealthTracker.ErrorCategory.RATE_LIMIT, "429");
        tracker.recordFailure(circuitOpen, ChannelHealthTracker.ErrorCategory.AUTH_ERROR, "401");
        awaitAsync();

        Set<Long> blocked = tracker.getBlockedChannelIds();
        assertTrue(blocked.contains(rateLimited));
        assertTrue(blocked.contains(circuitOpen));

        Set<Long> circuitOpenOnly = tracker.getCircuitOpenChannelIds();
        assertFalse(circuitOpenOnly.contains(rateLimited));
        assertTrue(circuitOpenOnly.contains(circuitOpen));
    }
}
