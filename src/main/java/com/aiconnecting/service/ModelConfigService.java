package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.entity.ModelConfig;
import com.aiconnecting.repository.ChannelRepository;
import com.aiconnecting.repository.ModelConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 模型配置服务
 */
@Service
@RequiredArgsConstructor
public class ModelConfigService {

    private final ModelConfigRepository modelConfigRepository;
    private final ChannelRepository channelRepository;

    public List<ModelConfig> listAll() {
        return modelConfigRepository.findAllByOrderByStatusDescNameAsc();
    }

    public List<ModelConfig> listNonAdmin() {
        return modelConfigRepository.findByAdminOnlyFalseOrderByStatusDescNameAsc();
    }

    public List<ModelConfig> listEnabled(boolean isAdmin) {
        return isAdmin
                ? modelConfigRepository.findByStatusOrderByStatusDescNameAsc(1)
                : modelConfigRepository.findByStatusAndAdminOnlyFalseOrderByStatusDescNameAsc(1);
    }

    public ModelConfig getById(Long id) {
        return modelConfigRepository.findById(id)
                .orElseThrow(() -> new BusinessException("模型不存在"));
    }

    public ModelConfig save(ModelConfig config) {
        return modelConfigRepository.save(config);
    }

    public void delete(Long id) {
        if (!modelConfigRepository.existsById(id)) {
            throw new BusinessException("模型不存在");
        }
        modelConfigRepository.deleteById(id);
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
        return modelConfigRepository.saveAll(configs);
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
