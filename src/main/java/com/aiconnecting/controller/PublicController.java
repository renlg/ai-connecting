package com.aiconnecting.controller;

import com.aiconnecting.common.ApiResponse;
import com.aiconnecting.entity.Announcement;
import com.aiconnecting.repository.AnnouncementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 公共接口（无需认证）
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PublicController {

    private final AnnouncementRepository announcementRepository;

    /**
     * 获取最新 N 条启用的公告
     */
    @GetMapping("/announcements/latest")
    public ApiResponse<List<Announcement>> getLatestAnnouncements(
            @RequestParam(defaultValue = "5") int limit) {
        return ApiResponse.success(announcementRepository.findLatestActive(limit));
    }
}
