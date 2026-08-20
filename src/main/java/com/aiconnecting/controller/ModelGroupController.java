package com.aiconnecting.controller;

import com.aiconnecting.common.ApiResponse;
import com.aiconnecting.common.BusinessException;
import com.aiconnecting.dto.ModelGroupRequest;
import com.aiconnecting.entity.ModelConfig;
import com.aiconnecting.entity.ModelGroup;
import com.aiconnecting.entity.ModelGroupMember;
import com.aiconnecting.service.ModelGroupService;
import com.aiconnecting.service.RelayService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型组管理 API（管理员）
 */
@RestController
@RequestMapping("/api/admin/model-groups")
@RequiredArgsConstructor
public class ModelGroupController {

    private final ModelGroupService modelGroupService;
    private final RelayService relayService;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.success(modelGroupService.listAll().stream().map(this::toDetail).toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable Long id) {
        ModelGroup group = modelGroupService.getById(id);
        return ApiResponse.success(toDetail(group));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody ModelGroupRequest request) {
        validateSupportedLevels(request.getSupportedLevels());
        ModelGroup group = fromRequest(new ModelGroup(), request);
        ModelGroup saved = modelGroupService.create(group, toMemberInputs(request));
        relayService.clearModelNameCache();
        return ApiResponse.success(toDetail(saved));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id, @RequestBody ModelGroupRequest request) {
        validateSupportedLevels(request.getSupportedLevels());
        ModelGroup patch = fromRequest(new ModelGroup(), request);
        ModelGroup saved = modelGroupService.update(id, patch, request.getMembers() != null ? toMemberInputs(request) : null);
        relayService.clearModelNameCache();
        return ApiResponse.success(toDetail(saved));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        modelGroupService.delete(id);
        relayService.clearModelNameCache();
        return ApiResponse.success();
    }

    @PutMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        Boolean enabled = body.get("enabled");
        if (enabled == null) {
            throw new BusinessException("enabled 不能为空", "enabled cannot be null");
        }
        ModelGroup patch = new ModelGroup();
        patch.setEnabled(enabled);
        modelGroupService.update(id, patch, null);
        relayService.clearModelNameCache();
        return ApiResponse.success();
    }

    private static final java.util.regex.Pattern SUPPORTED_LEVELS_PATTERN = java.util.regex.Pattern.compile("^[1-5](,[1-5])*$");

    private static void validateSupportedLevels(String supportedLevels) {
        if (supportedLevels == null || supportedLevels.isBlank()) {
            return;
        }
        if (!SUPPORTED_LEVELS_PATTERN.matcher(supportedLevels.trim()).matches()) {
            throw new BusinessException("支持等级格式非法，应为 1-5 的逗号分隔数字，例如: 1,2,3", "Invalid supported-level format; use comma-separated numbers from 1 to 5, for example: 1,2,3");
        }
    }

    private List<ModelGroupService.MemberInput> toMemberInputs(ModelGroupRequest request) {
        if (request.getMembers() == null) {
            return List.of();
        }
        return request.getMembers().stream()
                .map(m -> new ModelGroupService.MemberInput(m.getModelConfigId(), m.getWeight()))
                .toList();
    }

    private ModelGroup fromRequest(ModelGroup group, ModelGroupRequest request) {
        group.setName(request.getName());
        group.setType(request.getType());
        group.setStrategy(request.getStrategy());
        group.setMaxAttempts(request.getMaxAttempts());
        group.setEnabled(request.getEnabled());
        group.setAdminOnly(request.getAdminOnly());
        group.setInputPrice(request.getInputPrice());
        group.setOutputPrice(request.getOutputPrice());
        group.setCachedPrice(request.getCachedPrice());
        group.setPrice1k(request.getPrice1k());
        group.setPrice2k(request.getPrice2k());
        group.setPrice4k(request.getPrice4k());
        group.setPrice480p(request.getPrice480p());
        group.setPrice720p(request.getPrice720p());
        group.setPrice1080p(request.getPrice1080p());
        group.setVideoPrice4k(request.getVideoPrice4k());
        group.setPriceStandard(request.getPriceStandard());
        group.setPriceHd(request.getPriceHd());
        group.setSupportedLevels(request.getSupportedLevels());
        return group;
    }

    private Map<String, Object> toDetail(ModelGroup group) {
        List<ModelGroupService.MemberView> memberViews = modelGroupService.listMemberViews(group.getId());
        List<Map<String, Object>> members = memberViews.stream().map(mv -> {
            ModelGroupMember member = mv.member();
            ModelConfig config = mv.modelConfig();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", member.getId());
            m.put("modelConfigId", member.getModelConfigId());
            m.put("modelName", config.getName());
            m.put("modelDisplayName", config.getDisplayName());
            m.put("visionSupport", Boolean.TRUE.equals(config.getVisionSupport()));
            m.put("weight", member.getWeight());
            m.put("sortOrder", member.getSortOrder());
            return m;
        }).toList();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("group", group);
        detail.put("members", members);
        return detail;
    }
}
