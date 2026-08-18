package com.aiconnecting.controller;

import com.aiconnecting.common.ApiResponse;
import com.aiconnecting.entity.CircuitBreakerRecord;
import com.aiconnecting.entity.FailureStrategy;
import com.aiconnecting.entity.RiskPolicy;
import com.aiconnecting.entity.User;
import com.aiconnecting.service.OperationLogService;
import com.aiconnecting.service.RiskManagerService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/admin/risk")
@RequiredArgsConstructor
public class RiskManagerController {

    private final RiskManagerService riskManagerService;
    private final OperationLogService operationLogService;

    // ==================== Rate Limit Policies ====================

    @GetMapping("/policies")
    public ApiResponse<List<RiskPolicy>> listPolicies() {
        return ApiResponse.success(riskManagerService.listPolicies());
    }

    @PostMapping("/policies")
    public ApiResponse<RiskPolicy> createPolicy(@RequestBody RiskPolicy policy,
                                                 @AuthenticationPrincipal User currentUser) {
        RiskPolicy created = riskManagerService.createPolicy(policy);
        operationLogService.record(currentUser.getId(), "创建限速策略",
                String.valueOf(created.getId()),
                "渠道=" + created.getChannelId() + ", 模型=" + created.getModelConfigName());
        return ApiResponse.success(created);
    }

    @PutMapping("/policies/{id}")
    public ApiResponse<RiskPolicy> updatePolicy(@PathVariable Long id,
                                                 @RequestBody RiskPolicy policy,
                                                 @AuthenticationPrincipal User currentUser) {
        try {
            RiskPolicy updated = riskManagerService.updatePolicy(id, policy);
            operationLogService.record(currentUser.getId(), "更新限速策略",
                    String.valueOf(id), null);
            return ApiResponse.success(updated);
        } catch (NoSuchElementException e) {
            return ApiResponse.error(404, e.getMessage());
        }
    }

    @DeleteMapping("/policies/{id}")
    public ApiResponse<Void> deletePolicy(@PathVariable Long id,
                                           @AuthenticationPrincipal User currentUser) {
        riskManagerService.deletePolicy(id);
        operationLogService.record(currentUser.getId(), "删除限速策略", String.valueOf(id), null);
        return ApiResponse.success();
    }

    @PutMapping("/policies/{id}/status")
    public ApiResponse<RiskPolicy> updatePolicyStatus(@PathVariable Long id,
                                                       @RequestBody Map<String, Integer> body,
                                                       @AuthenticationPrincipal User currentUser) {
        try {
            Integer status = body.get("status");
            RiskPolicy updated = riskManagerService.updatePolicyStatus(id, status);
            operationLogService.record(currentUser.getId(), "更新限速策略状态",
                    String.valueOf(id), "status=" + status);
            return ApiResponse.success(updated);
        } catch (NoSuchElementException e) {
            return ApiResponse.error(404, e.getMessage());
        }
    }

    // ==================== Failure Strategies ====================

    @GetMapping("/failure-strategies")
    public ApiResponse<List<FailureStrategy>> listFailureStrategies() {
        return ApiResponse.success(riskManagerService.listFailureStrategies());
    }

    @PostMapping("/failure-strategies")
    public ApiResponse<FailureStrategy> createFailureStrategy(@RequestBody FailureStrategy strategy,
                                                               @AuthenticationPrincipal User currentUser) {
        FailureStrategy created = riskManagerService.createFailureStrategy(strategy);
        operationLogService.record(currentUser.getId(), "创建失败策略",
                String.valueOf(created.getId()),
                "scope=" + created.getScope() + ", channelId=" + created.getChannelId());
        return ApiResponse.success(created);
    }

    @PutMapping("/failure-strategies/{id}")
    public ApiResponse<FailureStrategy> updateFailureStrategy(@PathVariable Long id,
                                                               @RequestBody FailureStrategy strategy,
                                                               @AuthenticationPrincipal User currentUser) {
        try {
            FailureStrategy updated = riskManagerService.updateFailureStrategy(id, strategy);
            operationLogService.record(currentUser.getId(), "更新失败策略",
                    String.valueOf(id), null);
            return ApiResponse.success(updated);
        } catch (NoSuchElementException e) {
            return ApiResponse.error(404, e.getMessage());
        }
    }

    @DeleteMapping("/failure-strategies/{id}")
    public ApiResponse<Void> deleteFailureStrategy(@PathVariable Long id,
                                                    @AuthenticationPrincipal User currentUser) {
        riskManagerService.deleteFailureStrategy(id);
        operationLogService.record(currentUser.getId(), "删除失败策略", String.valueOf(id), null);
        return ApiResponse.success();
    }

    @PutMapping("/failure-strategies/{id}/status")
    public ApiResponse<FailureStrategy> updateFailureStrategyStatus(@PathVariable Long id,
                                                                     @RequestBody Map<String, Boolean> body,
                                                                     @AuthenticationPrincipal User currentUser) {
        try {
            Boolean enabled = body.get("enabled");
            FailureStrategy updated = riskManagerService.updateFailureStrategyStatus(id, enabled);
            operationLogService.record(currentUser.getId(), "更新失败策略状态",
                    String.valueOf(id), "enabled=" + enabled);
            return ApiResponse.success(updated);
        } catch (NoSuchElementException e) {
            return ApiResponse.error(404, e.getMessage());
        }
    }

    // ==================== Circuit Breaker Records ====================

    @GetMapping("/records")
    public ApiResponse<List<CircuitBreakerRecord>> listRecords() {
        return ApiResponse.success(riskManagerService.listRecords());
    }

    @PostMapping("/records/{id}/release")
    public ApiResponse<Void> releaseRecord(@PathVariable Long id,
                                            @AuthenticationPrincipal User currentUser) {
        try {
            riskManagerService.releaseRecord(id);
            operationLogService.record(currentUser.getId(), "手动解除熔断", String.valueOf(id), null);
            return ApiResponse.success();
        } catch (NoSuchElementException e) {
            return ApiResponse.error(404, e.getMessage());
        }
    }

    @PostMapping("/records/manual")
    public ApiResponse<CircuitBreakerRecord> createManualCircuitBreaker(@RequestBody Map<String, Object> body,
                                                                         @AuthenticationPrincipal User currentUser) {
        Long channelId = Long.valueOf(body.get("channelId").toString());
        String modelConfigName = body.get("modelConfigName") != null ? body.get("modelConfigName").toString() : null;
        int durationSeconds = Integer.parseInt(body.get("durationSeconds").toString());
        String reason = body.get("reason") != null ? body.get("reason").toString() : null;

        CircuitBreakerRecord record = riskManagerService.createManualCircuitBreaker(
                channelId, modelConfigName, durationSeconds, reason);
        operationLogService.record(currentUser.getId(), "手动添加熔断",
                String.valueOf(record.getId()),
                "渠道=" + channelId + ", 模型=" + modelConfigName + ", 时长=" + durationSeconds + "秒");
        return ApiResponse.success(record);
    }
}
