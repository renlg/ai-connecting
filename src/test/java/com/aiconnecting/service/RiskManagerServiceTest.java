package com.aiconnecting.service;

import com.aiconnecting.entity.CircuitBreakerRecord;
import com.aiconnecting.entity.FailureStrategy;
import com.aiconnecting.entity.RiskPolicy;
import com.aiconnecting.repository.CircuitBreakerRecordRepository;
import com.aiconnecting.repository.FailureStrategyRepository;
import com.aiconnecting.repository.RiskPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RiskManagerServiceTest {

    private RiskManagerService service;
    private RiskPolicyRepository policyRepo;
    private CircuitBreakerRecordRepository recordRepo;
    private FailureStrategyRepository failureStrategyRepo;

    @BeforeEach
    void setUp() {
        policyRepo = mock(RiskPolicyRepository.class);
        recordRepo = mock(CircuitBreakerRecordRepository.class);
        failureStrategyRepo = mock(FailureStrategyRepository.class);
        ObjectProvider<RedisTemplate<String, Long>> redisProvider = mock(ObjectProvider.class);
        ObjectProvider<RedisScript<Long>> scriptProvider = mock(ObjectProvider.class);
        ObjectProvider<ModelConfigService> modelConfigProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(null);
        when(scriptProvider.getIfAvailable()).thenReturn(null);
        when(modelConfigProvider.getIfAvailable()).thenReturn(null);

        service = new RiskManagerService(policyRepo, recordRepo, failureStrategyRepo,
                redisProvider, scriptProvider, modelConfigProvider);
    }

    // ==================== Existing Tests (Updated) ====================

    @Test
    void getWindowMsConvertsCorrectly() {
        assertEquals(60_000L, RiskManagerService.getWindowMs("MINUTE"));
        assertEquals(3_600_000L, RiskManagerService.getWindowMs("HOUR"));
        assertEquals(86_400_000L, RiskManagerService.getWindowMs("DAY"));
        assertEquals(0L, RiskManagerService.getWindowMs("INVALID"));
        assertEquals(0L, RiskManagerService.getWindowMs(null));
    }

    @Test
    void noPolicyMeansNoFusing() {
        when(policyRepo.findEnabledByChannelId(1L)).thenReturn(Collections.emptyList());
        when(policyRepo.findEnabledByChannelAndModel(1L, "gpt-4")).thenReturn(Collections.emptyList());

        boolean result = service.checkAndRecord(1L, "gpt-4");
        assertFalse(result);
    }

    @Test
    void fuseIsReturnedAfterTrigger() {
        RiskPolicy policy = RiskPolicy.builder()
                .id(1L).channelId(10L).modelConfigName("gpt-4")
                .rateLimit(2).timeWindow("MINUTE").windowType("SLIDING")
                .circuitBreakerDuration(300).status(1)
                .build();

        when(policyRepo.findEnabledByChannelId(10L)).thenReturn(Collections.emptyList());
        when(policyRepo.findEnabledByChannelAndModel(10L, "gpt-4")).thenReturn(List.of(policy));
        when(recordRepo.save(any())).thenAnswer(inv -> {
            CircuitBreakerRecord r = inv.getArgument(0);
            r.setId(100L);
            return r;
        });

        assertFalse(service.checkAndRecord(10L, "gpt-4"));
        assertFalse(service.checkAndRecord(10L, "gpt-4"));
        assertTrue(service.checkAndRecord(10L, "gpt-4"));
    }

    @Test
    void fusedChannelIdsIncludesTriggeredChannels() {
        RiskPolicy policy = RiskPolicy.builder()
                .id(1L).channelId(10L).modelConfigName(null)
                .rateLimit(1).timeWindow("MINUTE").windowType("SLIDING")
                .circuitBreakerDuration(300).status(1)
                .build();

        when(policyRepo.findEnabledByChannelId(10L)).thenReturn(List.of(policy));
        when(recordRepo.save(any())).thenAnswer(inv -> {
            CircuitBreakerRecord r = inv.getArgument(0);
            r.setId(100L);
            return r;
        });

        service.checkAndRecord(10L, null);
        service.checkAndRecord(10L, null);

        service.invalidateFusedCache();
        Set<Long> fused = service.getFusedChannelIds();
        assertTrue(fused.contains(10L));
    }

    @Test
    void channelLevelFuseBlocksAllModels() {
        RiskPolicy policy = RiskPolicy.builder()
                .id(1L).channelId(10L).modelConfigName(null)
                .rateLimit(1).timeWindow("MINUTE").windowType("SLIDING")
                .circuitBreakerDuration(300).status(1)
                .build();

        when(policyRepo.findEnabledByChannelId(10L)).thenReturn(List.of(policy));
        when(recordRepo.save(any())).thenAnswer(inv -> {
            CircuitBreakerRecord r = inv.getArgument(0);
            r.setId(100L);
            return r;
        });

        service.checkAndRecord(10L, null);
        assertTrue(service.checkAndRecord(10L, null));
        assertTrue(service.isChannelFusedForModel(10L, "gpt-4"));
        assertTrue(service.isChannelFusedForModel(10L, "claude-3"));
    }

    @Test
    void modelLevelFuseOnlyBlocksSpecificModel() {
        RiskPolicy policy = RiskPolicy.builder()
                .id(1L).channelId(10L).modelConfigName("gpt-4")
                .rateLimit(1).timeWindow("MINUTE").windowType("SLIDING")
                .circuitBreakerDuration(300).status(1)
                .build();

        when(policyRepo.findEnabledByChannelId(10L)).thenReturn(Collections.emptyList());
        when(policyRepo.findEnabledByChannelAndModel(10L, "gpt-4")).thenReturn(List.of(policy));
        when(recordRepo.save(any())).thenAnswer(inv -> {
            CircuitBreakerRecord r = inv.getArgument(0);
            r.setId(100L);
            return r;
        });

        service.checkAndRecord(10L, "gpt-4");
        assertTrue(service.checkAndRecord(10L, "gpt-4"));
        assertTrue(service.isChannelFusedForModel(10L, "gpt-4"));
        assertFalse(service.isChannelFusedForModel(10L, "claude-3"));
    }

    @Test
    void releaseRecordRemovesFuse() {
        RiskPolicy policy = RiskPolicy.builder()
                .id(1L).channelId(10L).modelConfigName(null)
                .rateLimit(1).timeWindow("MINUTE").windowType("SLIDING")
                .circuitBreakerDuration(300).status(1)
                .build();

        when(policyRepo.findEnabledByChannelId(10L)).thenReturn(List.of(policy));
        when(recordRepo.save(any())).thenAnswer(inv -> {
            CircuitBreakerRecord r = inv.getArgument(0);
            if (r.getId() == null) r.setId(100L);
            return r;
        });

        service.checkAndRecord(10L, null);
        assertTrue(service.checkAndRecord(10L, null));

        CircuitBreakerRecord record = CircuitBreakerRecord.builder()
                .id(100L).policyId(1L).channelId(10L).modelConfigName(null)
                .source("AUTO_RATE")
                .triggeredAt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusMinutes(5))
                .status("ACTIVE").build();
        when(recordRepo.findById(100L)).thenReturn(java.util.Optional.of(record));

        service.releaseRecord(100L);
        assertFalse(service.isChannelFusedForModel(10L, null));
    }

    @Test
    void resolveChannelModelIdReturnsNonNumericAsIs() {
        assertEquals("gpt-4", service.resolveChannelModelIdToName("gpt-4"));
        assertEquals("", service.resolveChannelModelIdToName(""));
        assertNull(service.resolveChannelModelIdToName(null));
    }

    // ==================== New Tests: Fixed Window ====================

    @Test
    void fixedWindowRateLimiting() {
        RiskPolicy policy = RiskPolicy.builder()
                .id(1L).channelId(10L).modelConfigName("gpt-4")
                .rateLimit(2).timeWindow("MINUTE").windowType("FIXED")
                .circuitBreakerDuration(300).status(1)
                .build();

        when(policyRepo.findEnabledByChannelId(10L)).thenReturn(Collections.emptyList());
        when(policyRepo.findEnabledByChannelAndModel(10L, "gpt-4")).thenReturn(List.of(policy));
        when(recordRepo.save(any())).thenAnswer(inv -> {
            CircuitBreakerRecord r = inv.getArgument(0);
            r.setId(100L);
            return r;
        });

        assertFalse(service.checkAndRecord(10L, "gpt-4"));
        assertFalse(service.checkAndRecord(10L, "gpt-4"));
        assertTrue(service.checkAndRecord(10L, "gpt-4"));
    }

    @Test
    void slidingWindowRateLimiting() {
        RiskPolicy policy = RiskPolicy.builder()
                .id(1L).channelId(10L).modelConfigName(null)
                .rateLimit(3).timeWindow("MINUTE").windowType("SLIDING")
                .circuitBreakerDuration(300).status(1)
                .build();

        when(policyRepo.findEnabledByChannelId(10L)).thenReturn(List.of(policy));
        when(recordRepo.save(any())).thenAnswer(inv -> {
            CircuitBreakerRecord r = inv.getArgument(0);
            r.setId(100L);
            return r;
        });

        assertFalse(service.checkAndRecord(10L, null));
        assertFalse(service.checkAndRecord(10L, null));
        assertFalse(service.checkAndRecord(10L, null));
        assertTrue(service.checkAndRecord(10L, null));
    }

    // ==================== New Tests: Failure Strategy Matching ====================

    @Test
    void matchesHttpCodesExactAndWildcard() {
        assertTrue(RiskManagerService.matchesHttpCodes("5xx,429", 500));
        assertTrue(RiskManagerService.matchesHttpCodes("5xx,429", 503));
        assertTrue(RiskManagerService.matchesHttpCodes("5xx,429", 429));
        assertFalse(RiskManagerService.matchesHttpCodes("5xx,429", 400));
        assertFalse(RiskManagerService.matchesHttpCodes("5xx,429", 200));
        assertTrue(RiskManagerService.matchesHttpCodes("4xx", 404));
        assertFalse(RiskManagerService.matchesHttpCodes("4xx", 500));
        assertFalse(RiskManagerService.matchesHttpCodes(null, 500));
        assertFalse(RiskManagerService.matchesHttpCodes("", 500));
    }

    @Test
    void globalFailureStrategyMatchesAnyChannel() {
        FailureStrategy strategy = FailureStrategy.builder()
                .id(1L).scope("GLOBAL").httpCodes("5xx")
                .windowType("SLIDING").windowDimension("MINUTE")
                .failureThreshold(2).fuseDurationSeconds(300)
                .priority(0).enabled(true)
                .build();

        when(failureStrategyRepo.findAllEnabledOrderByPriorityAsc()).thenReturn(List.of(strategy));
        when(recordRepo.save(any())).thenAnswer(inv -> {
            CircuitBreakerRecord r = inv.getArgument(0);
            if (r.getId() == null) r.setId(200L);
            return r;
        });

        service.recordFailureEvent(10L, "gpt-4", 500);
        service.recordFailureEvent(10L, "gpt-4", 500);
        assertTrue(service.isChannelFusedForModel(10L, "gpt-4"));

        service.recordFailureEvent(20L, "claude-3", 502);
        service.recordFailureEvent(20L, "claude-3", 502);
        assertTrue(service.isChannelFusedForModel(20L, "claude-3"));
    }

    @Test
    void channelFailureStrategyOnlyMatchesSpecificChannel() {
        FailureStrategy strategy = FailureStrategy.builder()
                .id(1L).scope("CHANNEL").channelId(10L)
                .httpCodes("5xx").windowType("SLIDING").windowDimension("MINUTE")
                .failureThreshold(1).fuseDurationSeconds(300)
                .priority(0).enabled(true)
                .build();

        when(failureStrategyRepo.findAllEnabledOrderByPriorityAsc()).thenReturn(List.of(strategy));
        when(recordRepo.save(any())).thenAnswer(inv -> {
            CircuitBreakerRecord r = inv.getArgument(0);
            if (r.getId() == null) r.setId(200L);
            return r;
        });

        service.recordFailureEvent(10L, "gpt-4", 500);
        assertTrue(service.isChannelFusedForModel(10L, "gpt-4"));

        service.recordFailureEvent(20L, "gpt-4", 500);
        assertFalse(service.isChannelFusedForModel(20L, "gpt-4"));
    }

    @Test
    void failureStrategyPriorityOrdering() {
        FailureStrategy highPriority = FailureStrategy.builder()
                .id(1L).scope("CHANNEL").channelId(10L)
                .httpCodes("5xx").windowType("SLIDING").windowDimension("MINUTE")
                .failureThreshold(1).fuseDurationSeconds(60)
                .priority(0).enabled(true)
                .build();
        FailureStrategy lowPriority = FailureStrategy.builder()
                .id(2L).scope("GLOBAL")
                .httpCodes("5xx").windowType("SLIDING").windowDimension("MINUTE")
                .failureThreshold(1).fuseDurationSeconds(600)
                .priority(10).enabled(true)
                .build();

        when(failureStrategyRepo.findAllEnabledOrderByPriorityAsc())
                .thenReturn(List.of(highPriority, lowPriority));
        when(recordRepo.save(any())).thenAnswer(inv -> {
            CircuitBreakerRecord r = inv.getArgument(0);
            if (r.getId() == null) r.setId(200L);
            return r;
        });

        service.recordFailureEvent(10L, "gpt-4", 500);

        verify(recordRepo).save(argThat(record ->
                record.getSource().equals("AUTO_FAILURE")
                        && record.getReason().contains("1")
        ));
    }

    @Test
    void nonMatchingHttpCodeDoesNotTrigger() {
        FailureStrategy strategy = FailureStrategy.builder()
                .id(1L).scope("GLOBAL").httpCodes("5xx")
                .windowType("SLIDING").windowDimension("MINUTE")
                .failureThreshold(1).fuseDurationSeconds(300)
                .priority(0).enabled(true)
                .build();

        when(failureStrategyRepo.findAllEnabledOrderByPriorityAsc()).thenReturn(List.of(strategy));

        service.recordFailureEvent(10L, "gpt-4", 400);
        assertFalse(service.isChannelFusedForModel(10L, "gpt-4"));
    }

    // ==================== New Tests: Manual Circuit Breaker ====================

    @Test
    void manualCircuitBreakerCreation() {
        when(recordRepo.save(any())).thenAnswer(inv -> {
            CircuitBreakerRecord r = inv.getArgument(0);
            if (r.getId() == null) r.setId(300L);
            return r;
        });

        CircuitBreakerRecord record = service.createManualCircuitBreaker(10L, "gpt-4", 600, "manual test");

        assertEquals("MANUAL", record.getSource());
        assertEquals("manual test", record.getReason());
        assertEquals(10L, record.getChannelId());
        assertEquals("gpt-4", record.getModelConfigName());
        assertTrue(service.isChannelFusedForModel(10L, "gpt-4"));
    }

    @Test
    void manualChannelLevelCircuitBreaker() {
        when(recordRepo.save(any())).thenAnswer(inv -> {
            CircuitBreakerRecord r = inv.getArgument(0);
            if (r.getId() == null) r.setId(300L);
            return r;
        });

        service.createManualCircuitBreaker(10L, null, 600, "channel-wide fuse");

        assertTrue(service.isChannelFusedForModel(10L, null));
        assertTrue(service.isChannelFusedForModel(10L, "gpt-4"));
        assertTrue(service.isChannelFusedForModel(10L, "claude-3"));
    }

    // ==================== New Tests: Model-Aware Filtering ====================

    @Test
    void modelAwareFuseFiltering() {
        when(recordRepo.save(any())).thenAnswer(inv -> {
            CircuitBreakerRecord r = inv.getArgument(0);
            if (r.getId() == null) r.setId(400L);
            return r;
        });

        service.createManualCircuitBreaker(10L, "gpt-4", 600, "model-specific");

        assertTrue(service.isChannelFusedForModel(10L, "gpt-4"));
        assertFalse(service.isChannelFusedForModel(10L, "claude-3"));

        service.invalidateFusedCache();
        Set<Long> fused = service.getFusedChannelIds();
        assertTrue(fused.contains(10L));
    }

    @Test
    void channelLevelFuseBlocksAllModelsInFilter() {
        when(recordRepo.save(any())).thenAnswer(inv -> {
            CircuitBreakerRecord r = inv.getArgument(0);
            if (r.getId() == null) r.setId(400L);
            return r;
        });

        service.createManualCircuitBreaker(10L, null, 600, "channel-level");

        assertTrue(service.isChannelFusedForModel(10L, "gpt-4"));
        assertTrue(service.isChannelFusedForModel(10L, "claude-3"));
        assertTrue(service.isChannelFusedForModel(10L, null));
    }

    @Test
    void circuitBreakerRecordSourceIsSet() {
        RiskPolicy policy = RiskPolicy.builder()
                .id(1L).channelId(10L).modelConfigName(null)
                .rateLimit(1).timeWindow("MINUTE").windowType("SLIDING")
                .circuitBreakerDuration(300).status(1)
                .build();

        when(policyRepo.findEnabledByChannelId(10L)).thenReturn(List.of(policy));
        when(recordRepo.save(any())).thenAnswer(inv -> {
            CircuitBreakerRecord r = inv.getArgument(0);
            if (r.getId() == null) r.setId(100L);
            return r;
        });

        service.checkAndRecord(10L, null);
        assertTrue(service.checkAndRecord(10L, null));

        verify(recordRepo).save(argThat(r -> "AUTO_RATE".equals(r.getSource())));
    }
}
