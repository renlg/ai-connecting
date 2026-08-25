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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Removes model-group members whose model is unavailable on every enabled channel because each
 * channel has a long-term circuit breaker. The model configuration is disabled in the same
 * transaction; channels and billing/usage data are left untouched.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LongTermCircuitBrokenModelPruningService {

    static final long LONG_TERM_BREAKER_SECONDS = 360L * 24 * 60 * 60;

    private static final String LOCK_KEY = "job:longTermCircuitBrokenModelPruning";
    private static final long LOCK_TTL_SECONDS = 300;
    private static final String PRUNING_REASON =
            "all enabled channels have ACTIVE circuit breakers lasting at least 360 days";
    private static final String DISABLING_REASON =
            "all enabled channels have long-term circuit breakers";

    private final ModelGroupMemberRepository modelGroupMemberRepository;
    private final ModelGroupRepository modelGroupRepository;
    private final ModelConfigRepository modelConfigRepository;
    private final ChannelRepository channelRepository;
    private final CircuitBreakerRecordRepository circuitBreakerRecordRepository;
    private final RedisDistributedLock distributedLock;
    private final CacheInvalidationService cacheInvalidationService;
    private final TransactionTemplate transactionTemplate;

    /** Defaults to enabled; configurable with MODEL_GROUP_PRUNING_ENABLED. */
    @Value("${app.model-group-pruning.enabled:true}")
    private boolean enabled;

    /**
     * Defaults to every ten minutes; configurable with MODEL_GROUP_PRUNING_INTERVAL_MS.
     * RedisDistributedLock falls back to local single-instance execution when Redis is disabled.
     */
    @Scheduled(fixedRateString = "${app.model-group-pruning.interval-ms:600000}", initialDelay = 120000)
    public void runScheduledPruning() {
        if (!enabled) {
            return;
        }
        distributedLock.runIfLocked(LOCK_KEY, LOCK_TTL_SECONDS, this::pruneNow);
    }

    void pruneNow() {
        List<DisabledModel> disabledModels = new ArrayList<>();
        Integer removed = transactionTemplate.execute(
                status -> pruneEligibleMembers(LocalDateTime.now(), disabledModels));
        if ((removed != null && removed > 0) || !disabledModels.isEmpty()) {
            // Publish after the pruning/disable transaction has committed so other instances cannot reload stale rows.
            cacheInvalidationService.publish(CacheInvalidationService.MODEL_CONFIG);
            for (DisabledModel model : disabledModels) {
                log.info("长期熔断模型自动禁用: modelId={}, model={}, reason={}",
                        model.id(), model.name(), DISABLING_REASON);
            }
            log.info("长期全渠道熔断模型剔除完成: removedMembers={}, disabledModels={}",
                    removed, disabledModels.size());
        }
    }

    int pruneEligibleMembers(LocalDateTime now) {
        return pruneEligibleMembers(now, new ArrayList<>());
    }

    private int pruneEligibleMembers(LocalDateTime now, List<DisabledModel> disabledModels) {
        List<ModelGroupMember> allMembers = modelGroupMemberRepository.findAll();
        if (allMembers.isEmpty()) {
            return 0;
        }

        Map<Long, List<ModelGroupMember>> membersByModelId = allMembers.stream()
                .collect(Collectors.groupingBy(ModelGroupMember::getModelConfigId));
        Map<Long, String> groupNames = modelGroupRepository.findAllById(allMembers.stream()
                        .map(ModelGroupMember::getGroupId)
                        .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(ModelGroup::getId, ModelGroup::getName));

        List<ModelConfig> memberModels = modelConfigRepository.findAllById(membersByModelId.keySet());
        int removedMembers = 0;
        for (ModelConfig model : memberModels) {
            List<Channel> enabledChannels = channelRepository
                    .findActiveChannelsByModel("%," + model.getId() + ",%");
            // An empty set is not vacuously considered unavailable: disabled/unmounted models must be retained.
            if (enabledChannels.isEmpty() || !allChannelsHaveLongTermBreaker(enabledChannels, model.getName(), now)) {
                continue;
            }

            List<ModelGroupMember> modelMembers = membersByModelId.getOrDefault(model.getId(), List.of());
            for (ModelGroupMember member : modelMembers) {
                String groupName = groupNames.getOrDefault(member.getGroupId(), "id=" + member.getGroupId());
                log.info("模型组成员自动剔除: group={}, model={}, reason={}",
                        groupName, model.getName(), PRUNING_REASON);
            }
            modelGroupMemberRepository.deleteAllInBatch(modelMembers);
            removedMembers += modelMembers.size();

            // The model is unavailable everywhere once every enabled channel is long-term broken.
            // Disable it in the same transaction as pruning; channel and billing/usage rows remain intact.
            if (!Integer.valueOf(0).equals(model.getStatus())) {
                model.setStatus(0);
                modelConfigRepository.save(model);
                disabledModels.add(new DisabledModel(model.getId(), model.getName()));
            }
        }
        return removedMembers;
    }

    private boolean allChannelsHaveLongTermBreaker(List<Channel> channels, String modelName, LocalDateTime now) {
        for (Channel channel : channels) {
            List<CircuitBreakerRecord> channelRecords = circuitBreakerRecordRepository
                    .findActiveByChannelIdAndModelIsNull(channel.getId(), now);
            if (channelRecords.stream().anyMatch(this::isLongTermBreaker)) {
                continue;
            }
            List<CircuitBreakerRecord> activeRecords = circuitBreakerRecordRepository
                    .findActiveByChannelAndModel(channel.getId(), modelName, now);
            if (activeRecords.stream().noneMatch(this::isLongTermBreaker)) {
                return false;
            }
        }
        return true;
    }

    private boolean isLongTermBreaker(CircuitBreakerRecord record) {
        if (record.getTriggeredAt() == null || record.getExpiresAt() == null) {
            return false;
        }
        return Duration.between(record.getTriggeredAt(), record.getExpiresAt()).getSeconds()
                >= LONG_TERM_BREAKER_SECONDS;
    }

    private record DisabledModel(Long id, String name) {
    }
}
