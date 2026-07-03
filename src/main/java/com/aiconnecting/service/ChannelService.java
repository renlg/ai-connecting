package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.dto.ChannelRequest;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.repository.ChannelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ChannelService {

    private final ChannelRepository channelRepository;

    public List<Channel> listAll() {
        return channelRepository.findAll();
    }

    public Channel getById(Long id) {
        return channelRepository.findById(id)
                .orElseThrow(() -> new BusinessException("渠道不存在"));
    }

    public Channel create(ChannelRequest request) {
        Channel channel = Channel.builder()
                .name(request.getName())
                .type(request.getType())
                .baseUrl(request.getBaseUrl())
                .apiKey(request.getApiKey())
                .models(request.getModels())
                .status(request.getStatus() != null ? request.getStatus() : 1)
                .priority(request.getPriority() != null ? request.getPriority() : 0)
                .rateLimit(request.getRateLimit() != null ? request.getRateLimit() : 0)
                .usedQuota(0L)
                .build();
        return channelRepository.save(channel);
    }

    public Channel update(Long id, ChannelRequest request) {
        Channel channel = getById(id);
        if (request.getName() != null) channel.setName(request.getName());
        if (request.getType() != null) channel.setType(request.getType());
        if (request.getBaseUrl() != null) channel.setBaseUrl(request.getBaseUrl());
        if (request.getApiKey() != null) channel.setApiKey(request.getApiKey());
        if (request.getModels() != null) channel.setModels(request.getModels());
        if (request.getStatus() != null) channel.setStatus(request.getStatus());
        if (request.getPriority() != null) channel.setPriority(request.getPriority());
        if (request.getRateLimit() != null) channel.setRateLimit(request.getRateLimit());
        return channelRepository.save(channel);
    }

    public void delete(Long id) {
        if (!channelRepository.existsById(id)) {
            throw new BusinessException("渠道不存在");
        }
        channelRepository.deleteById(id);
    }

    public void updateStatus(Long id, Integer status) {
        Channel channel = getById(id);
        channel.setStatus(status);
        channelRepository.save(channel);
    }

    /**
     * 根据模型名获取可用的渠道列表 (按优先级排序)
     */
    public List<Channel> getActiveChannelsByModel(String model) {
        String modelPattern = "%," + model + ",%";
        return channelRepository.findActiveChannelsByModel(modelPattern);
    }

    /**
     * 测试渠道连通性
     */
    public boolean testChannel(Long id) {
        Channel channel = getById(id);
        // 简单的连通性测试 - 实际可以通过发送一个简单请求来测试
        return channel.getBaseUrl() != null && !channel.getBaseUrl().isEmpty()
                && channel.getApiKey() != null && !channel.getApiKey().isEmpty();
    }
}
