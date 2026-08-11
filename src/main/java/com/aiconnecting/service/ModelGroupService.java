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

import java.math.BigDecimal;
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
                .orElseThrow(() -> new BusinessException("模型组不存在", "Model group not found"));
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
            throw new BusinessException("模型组类型无效，仅支持 text/image/video/audio", "Invalid model group type; only text/image/video/audio are supported");
        }
        return type;
    }

    static String normalizeStrategy(String strategy) {
        if (strategy == null || strategy.isBlank()) {
            return "round_robin";
        }
        if (!VALID_STRATEGIES.contains(strategy)) {
            throw new BusinessException("策略无效，仅支持 round_robin/priority/random", "Invalid strategy; only round_robin/priority/random are supported");
        }
        return strategy;
    }

    static int normalizeMaxAttempts(Integer maxAttempts) {
        if (maxAttempts == null) return 5;
        if (maxAttempts < 1) {
            throw new BusinessException("max_attempts 必须为正整数", "max_attempts must be a positive integer");
        }
        return Math.min(maxAttempts, MAX_ATTEMPTS_HARD_CAP);
    }

    private static void validatePrices(ModelGroup group) {
        validatePrice("inputPrice", group.getInputPrice());
        validatePrice("outputPrice", group.getOutputPrice());
        validatePrice("cachedPrice", group.getCachedPrice());
        validatePrice("price1k", group.getPrice1k());
        validatePrice("price2k", group.getPrice2k());
        validatePrice("price4k", group.getPrice4k());
        validatePrice("price480p", group.getPrice480p());
        validatePrice("price720p", group.getPrice720p());
        validatePrice("price1080p", group.getPrice1080p());
        validatePrice("price4k(video)", group.getVideoPrice4k());
        validatePrice("priceStandard", group.getPriceStandard());
        validatePrice("priceHd", group.getPriceHd());
    }

    private static void validatePrice(String field, BigDecimal value) {
        if (value != null && value.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(400, field + " 不能为负数", field + " cannot be negative");
        }
    }

    public void validateNameUnique(String name, Long excludeId) {
        if (name == null || name.isBlank()) {
            throw new BusinessException("模型组名称不能为空", "Model group name cannot be empty");
        }
        if (modelGroupRepository.existsByNameExcludingId(name, excludeId)) {
            throw new BusinessException("模型组名称 \"" + name + "\" 已存在",
                    "Model group name \"" + name + "\" already exists");
        }
        // 组名不得与任何单模型名称冲突：单模型请求按名称精确匹配路由，冲突会使原本走单模型路径的
        // 请求被劫持进组路径（不同的计费/权限逻辑），详见 RelayService#isGroupModel 的路由优先级约定
        if (!modelConfigRepository.findByName(name).isEmpty()) {
            throw new BusinessException(409, "模型组名称 \"" + name + "\" 与已存在的模型名称冲突",
                    "Model group name \"" + name + "\" conflicts with an existing model name");
        }
    }

    @Transactional
    public ModelGroup create(ModelGroup group, List<MemberInput> memberInputs) {
        validatePrices(group);
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
        validatePrices(patch);
        ModelGroup existing = getById(id);
        if (patch.getName() != null) {
            validateNameUnique(patch.getName(), id);
            existing.setName(patch.getName());
        }
        boolean typeChanged = patch.getType() != null && !patch.getType().equals(existing.getType());
        if (patch.getType() != null) {
            existing.setType(validateType(patch.getType()));
        }
        // 类型变更但本次请求未一并重新指定成员列表时，必须校验现有成员是否仍与新类型一致，
        // 否则组内会残留类型不匹配的成员（如文本组改成图片组后仍挂着文本模型），
        // 故障转移执行时按新类型渲染/计费会产生错误结果
        if (typeChanged && memberInputs == null) {
            List<ModelGroupMember> currentMembers = modelGroupMemberRepository.findByGroupIdOrderBySortOrderAsc(id);
            if (!currentMembers.isEmpty()) {
                Map<Long, ModelConfig> configs = modelConfigRepository
                        .findAllById(currentMembers.stream().map(ModelGroupMember::getModelConfigId).toList())
                        .stream().collect(Collectors.toMap(ModelConfig::getId, c -> c));
                List<String> mismatched = currentMembers.stream()
                        .map(m -> configs.get(m.getModelConfigId()))
                        .filter(c -> c != null)
                        .filter(c -> !existing.getType().equals(c.getType() == null || c.getType().isBlank() ? "text" : c.getType()))
                        .map(ModelConfig::getName)
                        .toList();
                if (!mismatched.isEmpty()) {
                throw new BusinessException(400, "模型组类型变更后，部分现有成员类型与新类型 (" + existing.getType()
                            + ") 不一致，请一并重新提交成员列表",
                        "After changing the model group type, some existing members do not match the new type ("
                                + existing.getType() + "); resubmit the member list");
                }
            }
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
        if (patch.getSupportedLevels() != null) {
            existing.setSupportedLevels(patch.getSupportedLevels());
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
                throw new BusinessException("成员模型 id 不能为空", "Member model ID cannot be null");
            }
            ModelConfig config = modelConfigRepository.findById(input.modelConfigId())
                    .orElseThrow(() -> new BusinessException("成员模型不存在: " + input.modelConfigId(),
                            "Member model not found: " + input.modelConfigId()));
            String configType = config.getType() == null || config.getType().isBlank() ? "text" : config.getType();
            if (!configType.equals(groupType)) {
                throw new BusinessException("成员模型类型 (" + configType + ") 与模型组类型 (" + groupType + ") 不一致",
                        "Member model type (" + configType + ") does not match model group type (" + groupType + ")");
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
            throw new BusinessException(409, "模型组仍有 " + memberCount + " 个成员，请先移除成员后再删除",
                    "The model group still has " + memberCount + " members; remove them before deleting the group");
        }
        modelGroupRepository.delete(group);
        publishInvalidation();
    }

    public void publishInvalidation() {
        cacheInvalidationService.publish(CacheInvalidationService.MODEL_CONFIG);
    }
}
