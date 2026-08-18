package com.aiconnecting.controller;

import com.aiconnecting.common.ApiResponse;
import com.aiconnecting.dto.ChannelRequest;
import com.aiconnecting.dto.ChannelResponse;
import com.aiconnecting.dto.StatusRequest;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.service.ChannelService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/channels")
@RequiredArgsConstructor
public class ChannelController {

    private final ChannelService channelService;

    @GetMapping
    public ApiResponse<List<ChannelResponse>> list(@RequestParam(required = false) String name,
                                                     @RequestParam(required = false) String modelIds) {
        List<String> modelIdList = (modelIds == null || modelIds.isBlank())
                ? null
                : Arrays.stream(modelIds.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
        return ApiResponse.success(channelService.listAll(name, modelIdList).stream()
                .map(ChannelResponse::fromChannel)
                .collect(Collectors.toList()));
    }

    @GetMapping("/{id}")
    public ApiResponse<ChannelResponse> getById(@PathVariable Long id) {
        return ApiResponse.success(ChannelResponse.fromChannel(channelService.getById(id)));
    }

    /**
     * 查看渠道明文 API Key
     */
    @GetMapping("/{id}/apikey")
    public ApiResponse<Map<String, String>> getApiKeyPlaintext(@PathVariable Long id) {
        Channel channel = channelService.getById(id);
        return ApiResponse.success(Map.of("apiKey", channel.getApiKey()));
    }

    @PostMapping
    public ApiResponse<ChannelResponse> create(@RequestBody ChannelRequest request) {
        return ApiResponse.success(ChannelResponse.fromChannel(channelService.create(request)));
    }

    @PutMapping("/{id}")
    public ApiResponse<ChannelResponse> update(@PathVariable Long id, @RequestBody ChannelRequest request) {
        return ApiResponse.success(ChannelResponse.fromChannel(channelService.update(id, request)));
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

    /** 测试图片、视频或音频模型（非流式）。 */
    @PostMapping("/test-media")
    public ApiResponse<Map<String, Object>> testMedia(@RequestBody Map<String, String> request) {
        return ApiResponse.success(channelService.testMedia(request));
    }

    /** 轮询渠道视频测试任务状态。 */
    @PostMapping("/test-video-status")
    public ApiResponse<Map<String, Object>> testVideoStatus(@RequestBody Map<String, String> request) {
        return ApiResponse.success(channelService.testVideoStatus(request));
    }

    /** 下载已完成的渠道视频测试结果，视频字节始终由后端携带渠道凭据获取。 */
    @PostMapping("/test-video-content")
    public ResponseEntity<byte[]> testVideoContent(@RequestBody Map<String, String> request) {
        ChannelService.TestMediaContent content = channelService.testVideoContent(request);
        MediaType contentType;
        try {
            contentType = MediaType.parseMediaType(content.contentType());
        } catch (Exception ignored) {
            contentType = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok().contentType(contentType).body(content.bytes());
    }
}
