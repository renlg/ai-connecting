package com.aiconnecting.controller;

import com.aiconnecting.common.ApiResponse;
import com.aiconnecting.dto.ChannelRequest;
import com.aiconnecting.dto.StatusRequest;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.service.ChannelHealthService;
import com.aiconnecting.service.ChannelHealthTracker;
import com.aiconnecting.service.ChannelService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/channels")
@RequiredArgsConstructor
public class ChannelController {

    private final ChannelService channelService;
    private final ChannelHealthService channelHealthService;
    private final ChannelHealthTracker channelHealthTracker;

    @GetMapping
    public ApiResponse<List<Channel>> list() {
        return ApiResponse.success(channelService.listAll());
    }

    @GetMapping("/{id}")
    public ApiResponse<Channel> getById(@PathVariable Long id) {
        return ApiResponse.success(channelService.getById(id));
    }

    @PostMapping
    public ApiResponse<Channel> create(@RequestBody ChannelRequest request) {
        return ApiResponse.success(channelService.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<Channel> update(@PathVariable Long id, @RequestBody ChannelRequest request) {
        return ApiResponse.success(channelService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        channelService.delete(id);
        return ApiResponse.success();
    }

    @PutMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        channelService.updateStatus(id, request.getStatus());
        return ApiResponse.success();
    }

    @PostMapping("/{id}/test")
    public ApiResponse<Boolean> test(@PathVariable Long id) {
        return ApiResponse.success(channelService.testChannel(id));
    }

    /**
     * 渠道健康看板：熔断器状态、SWRR 权重、错误率等
     */
    @GetMapping("/health")
    public ApiResponse<List<Map<String, Object>>> health() {
        return ApiResponse.success(channelHealthService.getAllChannelHealth());
    }

    /**
     * 手动解除渠道熔断封禁
     */
    @PostMapping("/{id}/unblock")
    public ApiResponse<Void> unblock(@PathVariable Long id) {
        channelHealthTracker.unblockChannel(id);
        return ApiResponse.success();
    }

    /**
     * 从上游渠道获取支持的模型列表
     */
    @PostMapping("/fetch-models")
    public ApiResponse<List<String>> fetchModels(@RequestBody Map<String, String> request) {
        String baseUrl = request.get("baseUrl");
        String apiKey = request.get("apiKey");
        String type = request.get("type");
        return ApiResponse.success(channelService.fetchUpstreamModels(baseUrl, apiKey, type));
    }

    /**
     * 测试渠道聊天功能（流式）
     */
    @PostMapping(value = "/test-chat-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void testChatStream(@RequestBody Map<String, String> request, HttpServletResponse response) throws Exception {
        channelService.testChatStream(request, response);
    }
}
