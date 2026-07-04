package com.aiconnecting.controller;

import com.aiconnecting.common.ApiResponse;
import com.aiconnecting.common.BusinessException;
import com.aiconnecting.dto.ModelConfigRequest;
import com.aiconnecting.entity.ModelConfig;
import com.aiconnecting.entity.User;
import com.aiconnecting.service.ModelConfigService;
import com.aiconnecting.service.RelayService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/models")
@RequiredArgsConstructor
public class ModelConfigController {

    private final ModelConfigService modelConfigService;
    private final RelayService relayService;

    /**
     * 获取所有模型配置（启用优先，按名称排序）
     * 管理员返回全部，普通用户只返回 adminOnly=false 的
     */
    @GetMapping
    public ApiResponse<List<ModelConfig>> list(@AuthenticationPrincipal User user) {
        boolean isAdmin = user != null && "admin".equalsIgnoreCase(user.getRole());
        if (isAdmin) {
            return ApiResponse.success(modelConfigService.listAll());
        }
        return ApiResponse.success(modelConfigService.listNonAdmin());
    }

    /**
     * 获取所有启用的模型（供渠道选择使用）
     * 管理员返回全部，普通用户只返回 adminOnly=false 的
     */
    @GetMapping("/enabled")
    public ApiResponse<List<ModelConfig>> listEnabled(@AuthenticationPrincipal User user) {
        boolean isAdmin = user != null && "admin".equalsIgnoreCase(user.getRole());
        if (isAdmin) {
            return ApiResponse.success(modelConfigService.listEnabled(true));
        }
        return ApiResponse.success(modelConfigService.listEnabled(false));
    }

    /**
     * 创建模型配置
     */
    @PostMapping
    public ApiResponse<ModelConfig> create(@RequestBody ModelConfigRequest request) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw new BusinessException("模型名称不能为空");
        }
        ModelConfig config = ModelConfig.builder()
                .name(request.getName())
                .displayName(request.getDisplayName())
                .description(request.getDescription())
                .inputCreditRate(request.getInputCreditRate() != null ? request.getInputCreditRate() : 0)
                .outputCreditRate(request.getOutputCreditRate() != null ? request.getOutputCreditRate() : 0)
                .adminOnly(Boolean.TRUE.equals(request.getAdminOnly()))
                .status(1)
                .build();
        ModelConfig saved = modelConfigService.save(config);
        relayService.clearModelNameCache();
        return ApiResponse.success(saved);
    }

    /**
     * 更新模型配置
     */
    @PutMapping("/{id}")
    public ApiResponse<ModelConfig> update(@PathVariable Long id, @RequestBody ModelConfigRequest request) {
        ModelConfig config = modelConfigService.getById(id);
        if (request.getName() != null) {
            config.setName(request.getName());
        }
        if (request.getDisplayName() != null) {
            config.setDisplayName(request.getDisplayName());
        }
        if (request.getDescription() != null) {
            config.setDescription(request.getDescription());
        }
        if (request.getInputCreditRate() != null) {
            config.setInputCreditRate(request.getInputCreditRate());
        }
        if (request.getOutputCreditRate() != null) {
            config.setOutputCreditRate(request.getOutputCreditRate());
        }
        if (request.getAdminOnly() != null) {
            config.setAdminOnly(request.getAdminOnly());
        }
        ModelConfig saved = modelConfigService.save(config);
        relayService.clearModelNameCache();
        return ApiResponse.success(saved);
    }

    /**
     * 删除模型配置
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        if (!modelConfigService.existsById(id)) {
            throw new BusinessException("模型不存在");
        }
        modelConfigService.delete(id);
        relayService.clearModelNameCache();
        return ApiResponse.success();
    }

    /**
     * 更新模型状态
     */
    @PutMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        ModelConfig config = modelConfigService.getById(id);
        config.setStatus(body.get("status"));
        modelConfigService.save(config);
        relayService.clearModelNameCache();
        return ApiResponse.success();
    }

    /**
     * 批量创建模型（快速添加）
     */
    @PostMapping("/batch")
    public ApiResponse<List<ModelConfig>> batchCreate(@RequestBody Map<String, List<String>> body) {
        List<String> names = body.get("names");
        if (names == null || names.isEmpty()) {
            throw new BusinessException("模型名称列表不能为空");
        }
        List<ModelConfig> created = modelConfigService.batchCreate(names);
        relayService.clearModelNameCache();
        return ApiResponse.success(created);
    }
}
