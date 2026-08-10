package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.common.CacheInvalidationService;
import com.aiconnecting.entity.ModelConfig;
import com.aiconnecting.entity.ModelGroup;
import com.aiconnecting.entity.ModelGroupMember;
import com.aiconnecting.repository.ModelConfigRepository;
import com.aiconnecting.repository.ModelGroupMemberRepository;
import com.aiconnecting.repository.ModelGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 模型组管理服务：CRUD、成员维护与相关校验。
 * 与 {@link ModelConfigService} 完全独立，不改动既有单模型管理逻辑。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModelGroupService {

    private final ModelGroupRepository modelGroupRepository;
    private final ModelGroupMemberRepository modelGroupMemberRepository;
    private final ModelConfigRepository modelConfigRepository;
    private final CacheInvalidationService cacheInvalidationService;

    static final Set<String> VALID_TYPES = Set.of("text", "image", "video", "audio");
    static final Set<String> VALID_STRATEGIES = Set.of("round_robin", "priority", "random");
    static final int MAX_ATTEMPTS_HARD_CAP = 8;

    /** 单个成员及其解析出的模型配置，供路由/管理端展示使用 */
    public record MemberView(ModelGroupMember member, ModelConfig modelConfig) {}

    public List<ModelGroup> listAll() {
        return modelGroupRepository.findAllByOrderByCreatedAtDesc();
    }

    public List<ModelGroup> listEnabled() {
        return modelGroupRepository.findByEnabledTrue();
    }

    public ModelGroup getById(Long id) {
        return modelGroupRepository.findById(id)
                .orElseThrow(() -> new BusinessException("模型组不存在"));
    }

    public java.util.Optional<ModelGroup> findByName(String name) {
        return modelGroupRepository.findByName(name);
    }

    public java.util.Optional<ModelGroup> findById(Long id) {
        return modelGroupRepository.findById(id);
    }

    public List<ModelGroupMember> listMembers(Long groupId) {
        return modelGroupMemberRepository.findByGroupIdOrderBySortOrderAsc(groupId);
    }

    /** 按 sortOrder 顺序返回成员及其解析出的模型配置（跳过已被删除的成员模型） */
    public List<MemberView> listMemberViews(Long groupId) {
        List<ModelGroupMember> members = listMembers(groupId);
        if (members.isEmpty()) {
            return List.of();
        }
        Map<Long, ModelConfig> configs = modelConfigRepository
                .findAllById(members.stream().map(ModelGroupMember::getModelConfigId).toList())
                .stream().collect(Collectors.toMap(ModelConfig::getId, c -> c));
        return members.stream()
                .filter(m -> configs.containsKey(m.getModelConfigId()))
                .map(m -> new MemberView(m, configs.get(m.getModelConfigId())))
                .toList();
    }

    static String validateType(String type) {
        if (type == null || type.isBlank() || !VALID_TYPES.contains(type)) {
            throw new BusinessException("模型组类型无效，仅支持 text/image/video/audio");
        }
        return type;
    }

    static String normalizeStrategy(String strategy) {
        if (strategy == null || strategy.isBlank()) {
            return "round_robin";
        }
        if (!VALID_STRATEGIES.contains(strategy)) {
            throw new BusinessException("策略无效，仅支持 round_robin/priority/random");
        }
        return strategy;
    }

    static int normalizeMaxAttempts(Integer maxAttempts) {
        if (maxAttempts == null) return 5;
        if (maxAttempts < 1) {
            throw new BusinessException("max_attempts 必须为正整数");
        }
        return Math.min(maxAttempts, MAX_ATTEMPTS_HARD_CAP);
    }

    public void validateNameUnique(String name, Long excludeId) {
        if (name == null || name.isBlank()) {
            throw new BusinessException("模型组名称不能为空");
        }
        if (modelGroupRepository.existsByNameExcludingId(name, excludeId)) {
            throw new BusinessException("模型组名称 \"" + name + "\" 已存在");
        }
    }

    @Transactional
    public ModelGroup create(ModelGroup group, List<MemberInput> memberInputs) {
        validateNameUnique(group.getName(), null);
        group.setType(validateType(group.getType()));
        group.setStrategy(normalizeStrategy(group.getStrategy()));
        group.setMaxAttempts(normalizeMaxAttempts(group.getMaxAttempts()));
        ModelGroup saved = modelGroupRepository.save(group);
        replaceMembers(saved.getId(), saved.getType(), memberInputs);
        publishInvalidation();
        return saved;
    }

    @Transactional
    public ModelGroup update(Long id, ModelGroup patch, List<MemberInput> memberInputs) {
        ModelGroup existing = getById(id);
        if (patch.getName() != null) {
            validateNameUnique(patch.getName(), id);
            existing.setName(patch.getName());
        }
        if (patch.getType() != null) {
            existing.setType(validateType(patch.getType()));
        }
        if (patch.getStrategy() != null) {
            existing.setStrategy(normalizeStrategy(patch.getStrategy()));
        }
        if (patch.getMaxAttempts() != null) {
            existing.setMaxAttempts(normalizeMaxAttempts(patch.getMaxAttempts()));
        }
        if (patch.getEnabled() != null) {
            existing.setEnabled(patch.getEnabled());
        }
        if (patch.getAdminOnly() != null) {
            existing.setAdminOnly(patch.getAdminOnly());
        }
        existing.setInputPrice(patch.getInputPrice() != null ? patch.getInputPrice() : existing.getInputPrice());
        existing.setOutputPrice(patch.getOutputPrice() != null ? patch.getOutputPrice() : existing.getOutputPrice());
        existing.setCachedPrice(patch.getCachedPrice() != null ? patch.getCachedPrice() : existing.getCachedPrice());
        existing.setPrice1k(patch.getPrice1k() != null ? patch.getPrice1k() : existing.getPrice1k());
        existing.setPrice2k(patch.getPrice2k() != null ? patch.getPrice2k() : existing.getPrice2k());
        existing.setPrice4k(patch.getPrice4k() != null ? patch.getPrice4k() : existing.getPrice4k());
        existing.setPrice480p(patch.getPrice480p() != null ? patch.getPrice480p() : existing.getPrice480p());
        existing.setPrice720p(patch.getPrice720p() != null ? patch.getPrice720p() : existing.getPrice720p());
        existing.setPrice1080p(patch.getPrice1080p() != null ? patch.getPrice1080p() : existing.getPrice1080p());
        existing.setVideoPrice4k(patch.getVideoPrice4k() != null ? patch.getVideoPrice4k() : existing.getVideoPrice4k());
        existing.setPriceStandard(patch.getPriceStandard() != null ? patch.getPriceStandard() : existing.getPriceStandard());
        existing.setPriceHd(patch.getPriceHd() != null ? patch.getPriceHd() : existing.getPriceHd());
        ModelGroup saved = modelGroupRepository.save(existing);
        if (memberInputs != null) {
            replaceMembers(saved.getId(), saved.getType(), memberInputs);
        }
        publishInvalidation();
        return saved;
    }

    /** 成员输入：模型配置 id + 权重（缺省 1），顺序即为传入列表顺序（映射为 sortOrder） */
    public record MemberInput(Long modelConfigId, Integer weight) {}

    @Transactional
    public void replaceMembers(Long groupId, String groupType, List<MemberInput> memberInputs) {
        modelGroupMemberRepository.deleteByGroupId(groupId);
        if (memberInputs == null || memberInputs.isEmpty()) {
            return;
        }
        int order = 0;
        for (MemberInput input : memberInputs) {
            if (input.modelConfigId() == null) {
                throw new BusinessException("成员模型 id 不能为空");
            }
            ModelConfig config = modelConfigRepository.findById(input.modelConfigId())
                    .orElseThrow(() -> new BusinessException("成员模型不存在: " + input.modelConfigId()));
            String configType = config.getType() == null || config.getType().isBlank() ? "text" : config.getType();
            if (!configType.equals(groupType)) {
                throw new BusinessException("成员模型 \"" + config.getName() + "\" 类型 (" + configType
                        + ") 与模型组类型 (" + groupType + ") 不一致");
            }
            modelGroupMemberRepository.save(ModelGroupMember.builder()
                    .groupId(groupId)
                    .modelConfigId(input.modelConfigId())
                    .weight(input.weight() != null && input.weight() > 0 ? input.weight() : 1)
                    .sortOrder(order++)
                    .build());
        }
    }

    @Transactional
    public void delete(Long id) {
        ModelGroup group = getById(id);
        long memberCount = modelGroupMemberRepository.countByGroupId(id);
        if (memberCount > 0) {
            throw new BusinessException(409, "模型组仍有 " + memberCount + " 个成员，请先移除成员后再删除");
        }
        modelGroupRepository.delete(group);
        publishInvalidation();
    }

    public void publishInvalidation() {
        cacheInvalidationService.publish(CacheInvalidationService.MODEL_CONFIG);
    }
}
