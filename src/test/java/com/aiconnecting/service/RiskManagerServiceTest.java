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

import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
    void allMatchingFailureStrategiesAreCounted() {
        FailureStrategy channelStrategy = FailureStrategy.builder()
                .id(1L).scope("CHANNEL").channelId(10L)
                .httpCodes("5xx").windowType("SLIDING").windowDimension("MINUTE")
                .failureThreshold(1).fuseDurationSeconds(60)
                .priority(0).enabled(true)
                .build();
        FailureStrategy globalStrategy = FailureStrategy.builder()
                .id(2L).scope("GLOBAL")
                .httpCodes("5xx").windowType("SLIDING").windowDimension("MINUTE")
                .failureThreshold(1).fuseDurationSeconds(600)
                .priority(10).enabled(true)
                .build();

        when(failureStrategyRepo.findAllEnabledOrderByPriorityAsc())
                .thenReturn(List.of(channelStrategy, globalStrategy));
        when(recordRepo.save(any())).thenAnswer(inv -> {
            CircuitBreakerRecord r = inv.getArgument(0);
            if (r.getId() == null) r.setId(200L);
            return r;
        });

        service.recordFailureEvent(10L, "gpt-4", 500);

        verify(recordRepo, times(2)).save(argThat(record ->
                record.getSource().equals("AUTO_FAILURE")
        ));
    }

    @Test
    void failureStrategyDedupPreventsDuplicateFuse() {
        FailureStrategy strategy = FailureStrategy.builder()
                .id(1L).scope("GLOBAL").httpCodes("5xx")
                .windowType("SLIDING").windowDimension("MINUTE")
                .failureThreshold(1).fuseDurationSeconds(300)
                .priority(0).enabled(true)
                .build();

        when(failureStrategyRepo.findAllEnabledOrderByPriorityAsc()).thenReturn(List.of(strategy));
        when(recordRepo.save(any())).thenAnswer(inv -> {
            CircuitBreakerRecord r = inv.getArgument(0);
            if (r.getId() == null) r.setId(200L);
            return r;
        });

        // Existing record with later expiry (10 min > 5 min from fuseDurationSeconds=300)
        CircuitBreakerRecord existingRecord = CircuitBreakerRecord.builder()
                .id(999L).policyId(1L).channelId(10L).modelConfigName("gpt-4")
                .source("AUTO_FAILURE")
                .triggeredAt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusMinutes(10))
                .status("ACTIVE").build();
        when(recordRepo.findActiveByChannelAndModel(eq(10L), eq("gpt-4"), any()))
                .thenReturn(List.of(existingRecord));

        service.recordFailureEvent(10L, "gpt-4", 500);

        // New expiry (5 min) <= existing (10 min) → skip write
        verify(recordRepo, never()).save(argThat(r ->
                "AUTO_FAILURE".equals(r.getSource())));
    }

    @Test
    void crossStrategyDedupPreventsShorterFuse() {
        FailureStrategy newStrategy = FailureStrategy.builder()
                .id(2L).scope("GLOBAL").httpCodes("5xx")
                .windowType("SLIDING").windowDimension("MINUTE")
                .failureThreshold(1).fuseDurationSeconds(60)
                .priority(0).enabled(true)
                .build();

        when(failureStrategyRepo.findAllEnabledOrderByPriorityAsc()).thenReturn(List.of(newStrategy));
        when(recordRepo.save(any())).thenAnswer(inv -> {
            CircuitBreakerRecord r = inv.getArgument(0);
            if (r.getId() == null) r.setId(200L);
            return r;
        });

        // Existing record from a DIFFERENT strategy (policyId=1) with longer expiry
        CircuitBreakerRecord existingRecord = CircuitBreakerRecord.builder()
                .id(999L).policyId(1L).channelId(10L).modelConfigName("gpt-4")
                .source("AUTO_FAILURE")
                .triggeredAt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusMinutes(10))
                .status("ACTIVE").build();
        when(recordRepo.findActiveByChannelAndModel(eq(10L), eq("gpt-4"), any()))
                .thenReturn(List.of(existingRecord));

        service.recordFailureEvent(10L, "gpt-4", 500);

        // New expiry (1 min) <= existing (10 min) → skip, even though different strategy
        verify(recordRepo, never()).save(argThat(r ->
                "AUTO_FAILURE".equals(r.getSource())));
    }

    @Test
    void longerNewFuseOverridesExistingShorter() {
        FailureStrategy strategy = FailureStrategy.builder()
                .id(2L).scope("GLOBAL").httpCodes("5xx")
                .windowType("SLIDING").windowDimension("MINUTE")
                .failureThreshold(1).fuseDurationSeconds(600)
                .priority(0).enabled(true)
                .build();

        when(failureStrategyRepo.findAllEnabledOrderByPriorityAsc()).thenReturn(List.of(strategy));
        when(recordRepo.save(any())).thenAnswer(inv -> {
            CircuitBreakerRecord r = inv.getArgument(0);
            if (r.getId() == null) r.setId(200L);
            return r;
        });

        // Existing record with shorter expiry (2 min)
        CircuitBreakerRecord existingRecord = CircuitBreakerRecord.builder()
                .id(999L).policyId(1L).channelId(10L).modelConfigName("gpt-4")
                .source("AUTO_FAILURE")
                .triggeredAt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusMinutes(2))
                .status("ACTIVE").build();
        when(recordRepo.findActiveByChannelAndModel(eq(10L), eq("gpt-4"), any()))
                .thenReturn(List.of(existingRecord));

        service.recordFailureEvent(10L, "gpt-4", 500);

        // New expiry (10 min) > existing (2 min) → should write
        verify(recordRepo).save(argThat(r ->
                "AUTO_FAILURE".equals(r.getSource()) && r.getExpiresAt().isAfter(existingRecord.getExpiresAt())));
    }

    @Test
    void modelConfigIdMatchingWorks() {
        FailureStrategy strategyWithModel = FailureStrategy.builder()
                .id(1L).scope("GLOBAL").modelConfigId(5L)
                .httpCodes("5xx").windowType("SLIDING").windowDimension("MINUTE")
                .failureThreshold(1).fuseDurationSeconds(300)
                .priority(0).enabled(true)
                .build();
        FailureStrategy strategyWithoutModel = FailureStrategy.builder()
                .id(2L).scope("GLOBAL")
                .httpCodes("5xx").windowType("SLIDING").windowDimension("MINUTE")
                .failureThreshold(1).fuseDurationSeconds(300)
                .priority(1).enabled(true)
                .build();

        when(failureStrategyRepo.findAllEnabledOrderByPriorityAsc())
                .thenReturn(List.of(strategyWithModel, strategyWithoutModel));
        when(recordRepo.save(any())).thenAnswer(inv -> {
            CircuitBreakerRecord r = inv.getArgument(0);
            if (r.getId() == null) r.setId(200L);
            return r;
        });

        // modelConfigId=5 matches both strategies
        service.processFailureEvent(10L, 5L, "gpt-4", 500);
        verify(recordRepo, times(2)).save(any());

        reset(recordRepo);
        when(recordRepo.save(any())).thenAnswer(inv -> {
            CircuitBreakerRecord r = inv.getArgument(0);
            if (r.getId() == null) r.setId(200L);
            return r;
        });

        // modelConfigId=99 only matches the strategy without model restriction
        service.processFailureEvent(10L, 99L, "claude-3", 500);
        verify(recordRepo, times(1)).save(argThat(r -> r.getPolicyId().equals(2L)));
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

    // ==================== P0 Fix Tests ====================

    @Test
    void checkRateLimitOnlySkipsFuseCheck() {
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

        assertFalse(service.checkRateLimitOnly(10L, "gpt-4"));
        assertTrue(service.checkRateLimitOnly(10L, "gpt-4"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void loadFusedChannelIdsUsesScanNotKeys() {
        RedisTemplate<String, Long> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, Long> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        long futureExpiry = System.currentTimeMillis() + 600_000;

        org.springframework.data.redis.core.Cursor<String> channelCursor = mock(org.springframework.data.redis.core.Cursor.class);
        when(channelCursor.hasNext()).thenReturn(true, false);
        when(channelCursor.next()).thenReturn("risk:cb:channel:42");

        org.springframework.data.redis.core.Cursor<String> modelCursor = mock(org.springframework.data.redis.core.Cursor.class);
        when(modelCursor.hasNext()).thenReturn(true, false);
        when(modelCursor.next()).thenReturn("risk:cb:model:99:gpt-4");

        org.springframework.data.redis.core.Cursor<String>[] cursors = new org.springframework.data.redis.core.Cursor[]{channelCursor, modelCursor};
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursors[0], cursors[1]);

        when(valueOps.multiGet(any(Collection.class))).thenAnswer(inv -> {
            Collection<String> keys = inv.getArgument(0);
            String first = keys.iterator().next();
            if (first.contains("channel")) return List.of(futureExpiry);
            return List.of(futureExpiry);
        });

        ObjectProvider<RedisTemplate<String, Long>> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(redisTemplate);
        ObjectProvider<RedisScript<Long>> scriptProvider = mock(ObjectProvider.class);
        when(scriptProvider.getIfAvailable()).thenReturn(null);
        ObjectProvider<ModelConfigService> modelConfigProvider = mock(ObjectProvider.class);
        when(modelConfigProvider.getIfAvailable()).thenReturn(null);

        RiskManagerService svc = new RiskManagerService(policyRepo, recordRepo, failureStrategyRepo,
                redisProvider, scriptProvider, modelConfigProvider);

        svc.invalidateFusedCache();
        Set<Long> fused = svc.getFusedChannelIds();

        assertTrue(fused.contains(42L));
        assertTrue(fused.contains(99L));
        verify(redisTemplate, never()).keys(anyString());
        verify(valueOps, never()).get(anyString());
    }

    @Test
    void invalidateFusedCacheForcesReload() {
        when(recordRepo.save(any())).thenAnswer(inv -> {
            CircuitBreakerRecord r = inv.getArgument(0);
            if (r.getId() == null) r.setId(500L);
            return r;
        });

        service.createManualCircuitBreaker(10L, "gpt-4", 600, "test");

        service.invalidateFusedCache();
        Set<Long> first = service.getFusedChannelIds();
        assertTrue(first.contains(10L));

        Set<Long> cached = service.getFusedChannelIds();
        assertSame(first, cached, "within 2s TTL, should return same cached instance");

        service.createManualCircuitBreaker(20L, "claude-3", 600, "test2");

        Set<Long> reloaded = service.getFusedChannelIds();
        assertTrue(reloaded.contains(10L));
        assertTrue(reloaded.contains(20L), "createManualCircuitBreaker invalidates cache, so reload includes both");
    }

    // ==================== P0 Fix Tests: Cleanup & Thread Pool ====================

    @SuppressWarnings("unchecked")
    private <T> T getField(String name) throws Exception {
        java.lang.reflect.Field f = RiskManagerService.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(service);
    }

    @Test
    void cleanupStaleFailureCounters_clearsSlidingButKeepsKey() throws Exception {
        ConcurrentHashMap<String, Deque<Long>> sliding = getField("memSlidingCounters");
        Deque<Long> dq = new ArrayDeque<>();
        long old = System.currentTimeMillis() - 86_400_000L * 3;
        dq.add(old);
        dq.add(old + 1000);
        sliding.put("risk:failure:1:10:gpt-4", dq);

        service.cleanupStaleFailureCounters();

        assertTrue(sliding.containsKey("risk:failure:1:10:gpt-4"),
                "滑动窗口 key 应保留，不删除");
        assertTrue(sliding.get("risk:failure:1:10:gpt-4").isEmpty(),
                "滑动窗口过期内容应被清空");
    }

    @Test
    void cleanupStaleFailureCounters_clearsFixedButKeepsKey() throws Exception {
        ConcurrentHashMap<String, long[]> fixed = getField("memFixedCounters");
        long old = System.currentTimeMillis() - 86_400_000L * 3;
        fixed.put("risk:failure:1:10:gpt-4", new long[]{old, 5});

        service.cleanupStaleFailureCounters();

        assertTrue(fixed.containsKey("risk:failure:1:10:gpt-4"),
                "固定窗口 key 应保留");
        assertEquals(0, fixed.get("risk:failure:1:10:gpt-4")[1],
                "固定窗口计数应被重置为 0");
    }

    @Test
    void cleanupStaleFailureCounters_removesExpiredFusedExpiry() throws Exception {
        ConcurrentHashMap<String, Long> fused = getField("memFusedExpiry");
        fused.put("channel:999", System.currentTimeMillis() - 100_000L);
        long futureExpiry = System.currentTimeMillis() + 600_000L;
        fused.put("channel:888", futureExpiry);

        service.cleanupStaleFailureCounters();

        assertFalse(fused.containsKey("channel:999"),
                "已过期的熔断缓存应被移除");
        assertTrue(fused.containsKey("channel:888"),
                "未过期的熔断缓存应保留");
        assertEquals(futureExpiry, fused.get("channel:888"));
    }

    @Test
    void cleanupCounterKey_clearsContentNotRemovesKey() throws Exception {
        FailureStrategy strategy = FailureStrategy.builder()
                .id(1L).scope("GLOBAL").httpCodes("5xx")
                .windowType("SLIDING").windowDimension("MINUTE")
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

        ConcurrentHashMap<String, Deque<Long>> sliding = getField("memSlidingCounters");
        String counterKey = "risk:failure:1:gpt-4";
        assertTrue(sliding.containsKey(counterKey), "熔断后 counter key 应存在");

        ConcurrentHashMap<String, Long> fused = getField("memFusedExpiry");
        fused.put("model:10:gpt-4", System.currentTimeMillis() - 1);
        service.invalidateFusedCache();

        service.recordFailureEvent(10L, "gpt-4", 500);

        assertTrue(sliding.containsKey(counterKey),
                "cleanupCounterKey 不应删除 key");
        assertTrue(sliding.get(counterKey).isEmpty(),
                "cleanupCounterKey 应清空 deque 内容");
    }

    @Test
    void slidingWindowConcurrentSafety_keyNeverRemoved() throws Exception {
        ConcurrentHashMap<String, Deque<Long>> sliding = getField("memSlidingCounters");

        Deque<Long> dq1 = new ArrayDeque<>();
        dq1.add(System.currentTimeMillis() - 86_400_000L * 3);
        sliding.put("test-key", dq1);

        service.cleanupStaleFailureCounters();

        Deque<Long> dqRef = sliding.get("test-key");
        assertNotNull(dqRef, "清理后 key 必须仍存在");
        assertSame(dq1, dqRef, "必须是同一个 deque 引用（computeIfAbsent 语义安全）");
    }
}
