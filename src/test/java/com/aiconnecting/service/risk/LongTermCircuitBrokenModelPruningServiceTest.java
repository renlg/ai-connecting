package com.aiconnecting.service.risk;

import com.aiconnecting.common.CacheInvalidationService;
import com.aiconnecting.common.RedisDistributedLock;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.entity.CircuitBreakerRecord;
import com.aiconnecting.entity.ModelConfig;
import com.aiconnecting.entity.ModelGroup;
import com.aiconnecting.entity.ModelGroupMember;
import com.aiconnecting.repository.ChannelRepository;
import com.aiconnecting.repository.CircuitBreakerRecordRepository;
import com.aiconnecting.repository.ModelConfigRepository;
import com.aiconnecting.repository.ModelGroupMemberRepository;
import com.aiconnecting.repository.ModelGroupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LongTermCircuitBrokenModelPruningServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 23, 12, 0);

    private ModelGroupMemberRepository memberRepository;
    private ModelGroupRepository groupRepository;
    private ModelConfigRepository modelRepository;
    private ChannelRepository channelRepository;
    private CircuitBreakerRecordRepository circuitBreakerRepository;
    private CacheInvalidationService cacheInvalidationService;
    private TransactionTemplate transactionTemplate;
    private LongTermCircuitBrokenModelPruningService service;

    @BeforeEach
    void setUp() {
        memberRepository = mock(ModelGroupMemberRepository.class);
        groupRepository = mock(ModelGroupRepository.class);
        modelRepository = mock(ModelConfigRepository.class);
        channelRepository = mock(ChannelRepository.class);
        circuitBreakerRepository = mock(CircuitBreakerRecordRepository.class);
        cacheInvalidationService = mock(CacheInvalidationService.class);
        transactionTemplate = mock(TransactionTemplate.class);
        service = new LongTermCircuitBrokenModelPruningService(
                memberRepository,
                groupRepository,
                modelRepository,
                channelRepository,
                circuitBreakerRepository,
                mock(RedisDistributedLock.class),
                cacheInvalidationService,
                transactionTemplate);
    }

    @Test
    void prunesModelWhenEveryEnabledChannelHasLongTermBreaker() {
        ModelGroupMember member = member(100L, 1L, 10L);
        arrangeMemberModel(member);
        Channel first = channel(11L, 1);
        Channel second = channel(12L, 1);
        when(channelRepository.findActiveChannelsByModel("%,10,%")).thenReturn(List.of(first, second));
        when(circuitBreakerRepository.findActiveByChannelAndModel(11L, "model-a", NOW))
                .thenReturn(List.of(breaker(360)));
        when(circuitBreakerRepository.findActiveByChannelAndModel(12L, "model-a", NOW))
                .thenReturn(List.of(breaker(3650)));

        int removed = service.pruneEligibleMembers(NOW);

        assertEquals(1, removed);
        verify(memberRepository).deleteAllInBatch(List.of(member));
    }

    @Test
    void keepsModelWhenOneEnabledChannelHasNoActiveBreaker() {
        ModelGroupMember member = member(100L, 1L, 10L);
        arrangeMemberModel(member);
        when(channelRepository.findActiveChannelsByModel("%,10,%"))
                .thenReturn(List.of(channel(11L, 1), channel(12L, 1)));
        when(circuitBreakerRepository.findActiveByChannelAndModel(11L, "model-a", NOW))
                .thenReturn(List.of(breaker(360)));
        when(circuitBreakerRepository.findActiveByChannelAndModel(12L, "model-a", NOW))
                .thenReturn(List.of());

        assertEquals(0, service.pruneEligibleMembers(NOW));

        verify(memberRepository, never()).deleteAllInBatch(any());
    }

    @Test
    void keepsModelWhenOneEnabledChannelOnlyHasShortBreaker() {
        ModelGroupMember member = member(100L, 1L, 10L);
        arrangeMemberModel(member);
        when(channelRepository.findActiveChannelsByModel("%,10,%"))
                .thenReturn(List.of(channel(11L, 1), channel(12L, 1)));
        when(circuitBreakerRepository.findActiveByChannelAndModel(11L, "model-a", NOW))
                .thenReturn(List.of(breaker(360)));
        when(circuitBreakerRepository.findActiveByChannelAndModel(12L, "model-a", NOW))
                .thenReturn(List.of(breaker(359)));

        assertEquals(0, service.pruneEligibleMembers(NOW));

        verify(memberRepository, never()).deleteAllInBatch(any());
    }

    @Test
    void disabledChannelDoesNotPreventPruning() {
        ModelGroupMember member = member(100L, 1L, 10L);
        arrangeMemberModel(member);
        // findActiveChannelsByModel excludes the disabled channel before it reaches the pruning rule.
        when(channelRepository.findActiveChannelsByModel("%,10,%"))
                .thenReturn(List.of(channel(11L, 1)));
        when(circuitBreakerRepository.findActiveByChannelAndModel(11L, "model-a", NOW))
                .thenReturn(List.of(breaker(360)));

        assertEquals(1, service.pruneEligibleMembers(NOW));

        verify(circuitBreakerRepository, never()).findActiveByChannelAndModel(eq(12L), any(), any());
        verify(memberRepository).deleteAllInBatch(List.of(member));
    }

    @Test
    void modelWithoutEnabledChannelsIsRetained() {
        ModelGroupMember member = member(100L, 1L, 10L);
        arrangeMemberModel(member);
        when(channelRepository.findActiveChannelsByModel("%,10,%")).thenReturn(List.of());

        assertEquals(0, service.pruneEligibleMembers(NOW));

        verifyNoInteractions(circuitBreakerRepository);
        verify(memberRepository, never()).deleteAllInBatch(any());
    }

    @Test
    void modelWithoutAnyGroupMembershipIsSkipped() {
        when(memberRepository.findAll()).thenReturn(List.of());

        assertEquals(0, service.pruneEligibleMembers(NOW));

        verifyNoInteractions(groupRepository, modelRepository, channelRepository, circuitBreakerRepository);
        verify(memberRepository, never()).deleteAllInBatch(any());
    }

    @Test
    void publishesModelCacheInvalidationAfterCommittedRemoval() {
        ModelGroupMember member = member(100L, 1L, 10L);
        arrangeMemberModel(member);
        when(channelRepository.findActiveChannelsByModel("%,10,%"))
                .thenReturn(List.of(channel(11L, 1)));
        when(circuitBreakerRepository.findActiveByChannelAndModel(eq(11L), eq("model-a"), any()))
                .thenReturn(List.of(breaker(360)));
        runTransactionCallbacksImmediately();

        service.pruneNow();

        verify(transactionTemplate).execute(any());
        verify(memberRepository).deleteAllInBatch(List.of(member));
        verify(cacheInvalidationService).publish(CacheInvalidationService.MODEL_CONFIG);
    }

    @Test
    void doesNotInvalidateModelCacheWhenNothingWasRemoved() {
        when(memberRepository.findAll()).thenReturn(List.of());
        runTransactionCallbacksImmediately();

        service.pruneNow();

        verify(cacheInvalidationService, never()).publish(any());
    }

    private void arrangeMemberModel(ModelGroupMember member) {
        when(memberRepository.findAll()).thenReturn(List.of(member));
        when(groupRepository.findAllById(any())).thenReturn(List.of(
                ModelGroup.builder().id(1L).name("group-a").build()));
        when(modelRepository.findAllById(any())).thenReturn(List.of(
                ModelConfig.builder().id(10L).name("model-a").build()));
    }

    private ModelGroupMember member(Long id, Long groupId, Long modelId) {
        return ModelGroupMember.builder().id(id).groupId(groupId).modelConfigId(modelId).build();
    }

    private Channel channel(Long id, int status) {
        return Channel.builder().id(id).status(status).modelIds("10").build();
    }

    private CircuitBreakerRecord breaker(long days) {
        return CircuitBreakerRecord.builder()
                .channelId(11L)
                .modelConfigName("model-a")
                .status("ACTIVE")
                .triggeredAt(NOW.minusDays(1))
                .expiresAt(NOW.minusDays(1).plusDays(days))
                .build();
    }

    @SuppressWarnings("unchecked")
    private void runTransactionCallbacksImmediately() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Integer> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }
}
