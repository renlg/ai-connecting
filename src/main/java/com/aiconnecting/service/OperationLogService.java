package com.aiconnecting.service;

import com.aiconnecting.entity.OperationLog;
import com.aiconnecting.repository.OperationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class OperationLogService {

    private final OperationLogRepository operationLogRepository;

    /**
     * 记录一次管理员操作
     */
    public void record(Long adminId, String action, String target, String detail) {
        try {
            OperationLog logEntry = OperationLog.builder()
                    .adminId(adminId)
                    .action(action)
                    .target(target)
                    .detail(detail)
                    .build();
            operationLogRepository.save(logEntry);
        } catch (Exception e) {
            // 审计日志写入失败不应影响正常业务流程
            log.error("记录管理员操作日志失败: admin={}, action={}, target={}", adminId, action, target, e);
        }
    }

    public Page<OperationLog> getLogs(int page, int size) {
        return operationLogRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size));
    }
}
