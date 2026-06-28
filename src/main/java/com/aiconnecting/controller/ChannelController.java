package com.aiconnecting.controller;

import com.aiconnecting.common.ApiResponse;
import com.aiconnecting.dto.ChannelRequest;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.service.ChannelService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/channels")
@RequiredArgsConstructor
public class ChannelController {

    private final ChannelService channelService;

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
    public ApiResponse<Void> updateStatus(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        channelService.updateStatus(id, body.get("status"));
        return ApiResponse.success();
    }

    @PostMapping("/{id}/test")
    public ApiResponse<Boolean> test(@PathVariable Long id) {
        return ApiResponse.success(channelService.testChannel(id));
    }
}
