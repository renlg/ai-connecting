package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.common.CacheInvalidationService;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.entity.ModelConfig;
import com.aiconnecting.repository.ChannelRepository;
import com.aiconnecting.repository.ModelConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模型配置服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModelConfigService {

    private final ModelConfigRepository modelConfigRepository;
    private final ChannelRepository channelRepository;
    private final CacheInvalidationService cacheInvalidationService;

    /** 按 model_configs.name 索引的模型信息（type/displayName），用于仪表盘按模型名合并统计，避免与 usage_logs JOIN */
    public record ModelInfo(String type, String displayName) {}

    private static final long MODEL_INFO_CACHE_TTL_MS = 3 * 60 * 1000L;
    private volatile CachedModelInfoMap modelInfoMapCache;

    private record CachedModelInfoMap(Map<String, ModelInfo> map, long cachedAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > MODEL_INFO_CACHE_TTL_MS;
        }
    }

    /**
     * 获取 name -> (type, displayName) 映射，用于仪表盘将按模型名分组的统计结果合并为按类型统计，
     * 从而避免 usage_logs JOIN model_configs（同名多行会产生笛卡尔积）。缓存 3 分钟。
     */
    public Map<String, ModelInfo> getModelInfoMap() {
        CachedModelInfoMap cached = modelInfoMapCache;
        if (cached != null && !cached.isExpired()) {
            return cached.map();
        }
        long generation = cacheInvalidationService.generation(CacheInvalidationService.MODEL_CONFIG);
        Map<String, ModelInfo> map = new HashMap<>();
        for (ModelConfig mc : modelConfigRepository.findAll()) {
            ModelInfo existing = map.get(mc.getName());
            if (existing != null) {
                if (!existing.type().equals(mc.getType())) {
                    log.warn("model_configs 中模型名 '{}' 存在不同的 type（'{}' vs '{}'），按先出现的为准",
                            mc.getName(), existing.type(), mc.getType());
                }
                continue;
            }
            map.put(mc.getName(), new ModelInfo(mc.getType(), mc.getDisplayName()));
        }
        if (cacheInvalidationService.isCurrentGeneration(CacheInvalidationService.MODEL_CONFIG, generation)) {
            CachedModelInfoMap fresh = new CachedModelInfoMap(map, System.currentTimeMillis());
            modelInfoMapCache = fresh;
            if (!cacheInvalidationService.isCurrentGeneration(CacheInvalidationService.MODEL_CONFIG, generation)
                    && modelInfoMapCache == fresh) {
                modelInfoMapCache = null;
            }
        }
        return map;
    }

    public List<ModelConfig> listAll() {
        return modelConfigRepository.findAllOrderByCreatedAtDesc();
    }

    public List<ModelConfig> listAll(String name, String type) {
        return modelConfigRepository.searchAll(normalizeFilter(name), normalizeFilter(type));
    }

    public List<ModelConfig> listNonAdmin() {
        return modelConfigRepository.findByAdminOnlyFalseOrderByCreatedAtDesc();
    }

    public List<ModelConfig> listNonAdmin(String name, String type) {
        return modelConfigRepository.searchNonAdmin(normalizeFilter(name), normalizeFilter(type));
    }

    private String normalizeFilter(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public List<ModelConfig> listEnabled(boolean isAdmin) {
        return isAdmin
                ? modelConfigRepository.findByStatusOrderByStatusDescNameAsc(1)
                : modelConfigRepository.findByStatusAndAdminOnlyFalseOrderByStatusDescNameAsc(1);
    }

    public ModelConfig getById(Long id) {
        return modelConfigRepository.findById(id)
                .orElseThrow(() -> new BusinessException("模型不存在", "Model not found"));
    }

    public ModelConfig save(ModelConfig config) {
        ModelConfig saved = modelConfigRepository.save(config);
        cacheInvalidationService.publish(CacheInvalidationService.MODEL_CONFIG);
        return saved;
    }

    /**
     * 校验 displayName 唯一性（display_name 是平台侧模型唯一标识）。
     * 更新场景下排除自身 id；displayName 为空时不做校验。
     */
    public void validateDisplayNameUnique(String displayName, Long excludeId) {
        if (displayName == null || displayName.isBlank()) {
            return;
        }
        if (modelConfigRepository.existsByDisplayNameExcludingId(displayName, excludeId)) {
            throw new BusinessException("显示名称 \"" + displayName + "\" 已存在，请使用唯一的显示名称",
                    "Display name \"" + displayName + "\" already exists; use a unique display name");
        }
    }

    public void delete(Long id) {
        if (!modelConfigRepository.existsById(id)) {
            throw new BusinessException("模型不存在", "Model not found");
        }
        modelConfigRepository.deleteById(id);
        cacheInvalidationService.publish(CacheInvalidationService.MODEL_CONFIG);
    }

    public boolean existsById(Long id) {
        return modelConfigRepository.existsById(id);
    }

    /**
     * 按模型名称查找
     */
    public List<ModelConfig> findByName(String name) {
        return modelConfigRepository.findByName(name);
    }

    /**
     * 按显示名称查找
     */
    public List<ModelConfig> findByDisplayName(String displayName) {
        return modelConfigRepository.findByDisplayName(displayName);
    }

    /**
     * 批量创建模型配置（事务性操作）
     */
    @Transactional
    public List<ModelConfig> batchCreate(List<String> names) {
        List<ModelConfig> configs = names.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(name -> ModelConfig.builder().name(name).status(1).build())
                .toList();
        List<ModelConfig> saved = modelConfigRepository.saveAll(configs);
        cacheInvalidationService.publish(CacheInvalidationService.MODEL_CONFIG);
        return saved;
    }

    @EventListener
    public void onCacheInvalidation(CacheInvalidationService.CacheInvalidationEvent event) {
        if (CacheInvalidationService.MODEL_CONFIG.equals(event.route())) {
            modelInfoMapCache = null;
        }
    }

    /**
     * 获取所有启用渠道配置的模型ID集合
     */
    public Set<String> getActiveChannelModelIds() {
        Set<String> channelModelIds = new LinkedHashSet<>();
        for (Channel channel : channelRepository.findByStatusOrderByPriorityDesc(1)) {
            if (channel.getModelIds() != null && !channel.getModelIds().isEmpty()) {
                for (String modelId : channel.getModelIds().split(",")) {
                    channelModelIds.add(modelId.trim());
                }
            }
        }
        return channelModelIds;
    }

    /**
     * 获取可用模型列表（有启用渠道支持的启用模型）
     */
    public List<ModelConfig> getAvailableModels(boolean isAdmin) {
        List<ModelConfig> models = listEnabled(isAdmin);
        Set<String> channelModelIds = getActiveChannelModelIds();
        return models.stream()
                .filter(model -> channelModelIds.contains(String.valueOf(model.getId())))
                .toList();
    }
}
