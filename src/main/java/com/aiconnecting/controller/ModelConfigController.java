package com.aiconnecting.controller;

import com.aiconnecting.common.ApiResponse;
import com.aiconnecting.common.BusinessException;
import com.aiconnecting.dto.ModelConfigRequest;
import com.aiconnecting.dto.StatusRequest;
import com.aiconnecting.entity.ModelConfig;
import com.aiconnecting.entity.ModelGroup;
import com.aiconnecting.entity.User;
import com.aiconnecting.service.ModelConfigService;
import com.aiconnecting.service.ModelGroupService;
import com.aiconnecting.service.RelayService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/admin/models")
@RequiredArgsConstructor
public class ModelConfigController {

    private final ModelConfigService modelConfigService;
    private final ModelGroupService modelGroupService;
    private final RelayService relayService;

    /** 故障转移组必须存在且类型与本模型一致，否则请求会在切换到组内成员时类型不匹配 */
    private void validateFallbackGroup(Long fallbackGroupId, String modelType) {
        if (fallbackGroupId == null) {
            return;
        }
        ModelGroup group = modelGroupService.getById(fallbackGroupId);
        String effectiveType = modelType != null ? modelType : "text";
        if (!effectiveType.equals(group.getType())) {
            throw new BusinessException("故障转移组类型 (" + group.getType() + ") 与模型类型 (" + effectiveType + ") 不一致",
                    "Failover group type (" + group.getType() + ") does not match model type (" + effectiveType + ")");
        }
    }

    /** 模型名称/显示名不得与已存在的模型组名称冲突，理由同 {@link ModelGroupService#guardDuplicateName} */
    private void validateNotGroupName(String name) {
        if (name != null && !name.isBlank() && modelGroupService.findByName(name).isPresent()) {
            throw new BusinessException(409, "模型名称 \"" + name + "\" 与已存在的模型组名称冲突",
                    "Model name \"" + name + "\" conflicts with an existing model group name");
        }
    }

    private static final Set<String> VALID_TYPES = Set.of("text", "image", "video", "audio");

    private static String validateType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        if (!VALID_TYPES.contains(type)) {
            throw new BusinessException("模型类型无效，仅支持 text/image/video/audio", "Invalid model type; only text/image/video/audio are supported");
        }
        return type;
    }

    /**
     * 校验所有价格/比例字段均为非负数（文本输入/输出/缓存比例、图片与视频各档位价格）
     */
    private static void validatePrices(ModelConfigRequest request) {
        checkNonNegative("输入积分比例", request.getInputCreditRate());
        checkNonNegative("输出积分比例", request.getOutputCreditRate());
        checkNonNegative("缓存积分比例", request.getCacheCreditRate());
        checkNonNegative("图片 1K 档价格", request.getImagePrice1k());
        checkNonNegative("图片 2K 档价格", request.getImagePrice2k());
        checkNonNegative("图片 4K 档价格", request.getImagePrice4k());
        checkNonNegative("视频 480P 档价格", request.getVideoPrice480p());
        checkNonNegative("视频 720P 档价格", request.getVideoPrice720p());
        checkNonNegative("视频 1080P 档价格", request.getVideoPrice1080p());
        checkNonNegative("视频 4K 档价格", request.getVideoPrice4k());
        checkNonNegative("音频标准档价格", request.getAudioPriceStandard());
        checkNonNegative("音频高清档价格", request.getAudioPriceHd());
    }

    private static void checkNonNegative(String label, Integer value) {
        if (value != null && value < 0) {
            throw new BusinessException(label + "不能为负数", label + " cannot be negative");
        }
    }

    private static void checkNonNegative(String label, BigDecimal value) {
        if (value != null && value.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(label + "不能为负数", label + " cannot be negative");
        }
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

    /**
     * 获取所有模型配置（启用优先，按名称排序）
     * 管理员返回全部，普通用户只返回 adminOnly=false 的
     */
    @GetMapping
    public ApiResponse<List<ModelConfig>> list(@AuthenticationPrincipal User user,
                                                @RequestParam(required = false) String name,
                                                @RequestParam(required = false) String type) {
        boolean isAdmin = user != null && "admin".equalsIgnoreCase(user.getRole());
        boolean hasFilters = (name != null && !name.isBlank()) || (type != null && !type.isBlank());
        if (isAdmin) {
            return ApiResponse.success(hasFilters
                    ? modelConfigService.listAll(name, type)
                    : modelConfigService.listAll());
        }
        return ApiResponse.success(hasFilters
                ? modelConfigService.listNonAdmin(name, type)
                : modelConfigService.listNonAdmin());
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
            throw new BusinessException("模型名称不能为空", "Model name cannot be empty");
        }
        modelConfigService.guardDuplicateDisplayName(request.getDisplayName());
        validateNotGroupName(request.getName());
        validateNotGroupName(request.getDisplayName());
        validatePrices(request);
        String type = validateType(request.getType());
        validateFallbackGroup(request.getFallbackGroupId(), type != null ? type : "text");
        validateSupportedLevels(request.getSupportedLevels());
        ModelConfig config = ModelConfig.builder()
                .name(request.getName())
                .displayName(request.getDisplayName())
                .description(request.getDescription())
                .type(type != null ? type : "text")
                .visionSupport("text".equals(type != null ? type : "text")
                        && Boolean.TRUE.equals(request.getVisionSupport()))
                .inputCreditRate(request.getInputCreditRate() != null ? request.getInputCreditRate() : 0)
                .outputCreditRate(request.getOutputCreditRate() != null ? request.getOutputCreditRate() : 0)
                .adminOnly(Boolean.TRUE.equals(request.getAdminOnly()))
                .cacheCreditRate(request.getCacheCreditRate() != null ? request.getCacheCreditRate() : BigDecimal.ZERO)
                .imagePrice1k(request.getImagePrice1k())
                .imagePrice2k(request.getImagePrice2k())
                .imagePrice4k(request.getImagePrice4k())
                .videoPrice480p(request.getVideoPrice480p())
                .videoPrice720p(request.getVideoPrice720p())
                .videoPrice1080p(request.getVideoPrice1080p())
                .videoPrice4k(request.getVideoPrice4k())
                .audioPriceStandard(request.getAudioPriceStandard())
                .audioPriceHd(request.getAudioPriceHd())
                .fallbackGroupId(request.getFallbackGroupId())
                .supportedLevels(request.getSupportedLevels())
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
        validatePrices(request);
        validateSupportedLevels(request.getSupportedLevels());
        ModelConfig config = modelConfigService.getById(id);
        if (request.getName() != null) {
            validateNotGroupName(request.getName());
            config.setName(request.getName());
        }
        if (request.getDisplayName() != null) {
            modelConfigService.guardDuplicateDisplayName(request.getDisplayName());
            validateNotGroupName(request.getDisplayName());
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
        if (request.getCacheCreditRate() != null) {
            config.setCacheCreditRate(request.getCacheCreditRate());
        }
        String type = validateType(request.getType());
        if (type != null) {
            config.setType(type);
        }
        if (!"text".equals(config.getType())) {
            config.setVisionSupport(false);
        } else if (request.getVisionSupport() != null) {
            config.setVisionSupport(request.getVisionSupport());
        }
        if (request.getImagePrice1k() != null) {
            config.setImagePrice1k(request.getImagePrice1k());
        }
        if (request.getImagePrice2k() != null) {
            config.setImagePrice2k(request.getImagePrice2k());
        }
        if (request.getImagePrice4k() != null) {
            config.setImagePrice4k(request.getImagePrice4k());
        }
        if (request.getVideoPrice480p() != null) {
            config.setVideoPrice480p(request.getVideoPrice480p());
        }
        if (request.getVideoPrice720p() != null) {
            config.setVideoPrice720p(request.getVideoPrice720p());
        }
        if (request.getVideoPrice1080p() != null) {
            config.setVideoPrice1080p(request.getVideoPrice1080p());
        }
        if (request.getVideoPrice4k() != null) {
            config.setVideoPrice4k(request.getVideoPrice4k());
        }
        if (request.getAudioPriceStandard() != null) {
            config.setAudioPriceStandard(request.getAudioPriceStandard());
        }
        if (request.getAudioPriceHd() != null) {
            config.setAudioPriceHd(request.getAudioPriceHd());
        }
        if (request.getFallbackGroupId() != null) {
            validateFallbackGroup(request.getFallbackGroupId() == 0 ? null : request.getFallbackGroupId(), config.getType());
            config.setFallbackGroupId(request.getFallbackGroupId() == 0 ? null : request.getFallbackGroupId());
        }
        if (request.getSupportedLevels() != null) {
            config.setSupportedLevels(request.getSupportedLevels());
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
            throw new BusinessException("模型不存在", "Model not found");
        }
        modelConfigService.delete(id);
        relayService.clearModelNameCache();
        return ApiResponse.success();
    }

    /**
     * 更新模型状态
     */
    @PutMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        ModelConfig config = modelConfigService.getById(id);
        config.setStatus(request.getStatus());
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
            throw new BusinessException("模型名称列表不能为空", "Model name list cannot be empty");
        }
        names.stream().filter(name -> name != null && !name.isBlank()).forEach(this::validateNotGroupName);
        List<ModelConfig> created = modelConfigService.batchCreate(names);
        relayService.clearModelNameCache();
        return ApiResponse.success(created);
    }
}
