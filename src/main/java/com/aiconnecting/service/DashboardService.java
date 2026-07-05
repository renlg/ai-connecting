package com.aiconnecting.service;

import com.aiconnecting.dto.DashboardStats;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.entity.Token;
import com.aiconnecting.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final ChannelService channelService;
    private final ChannelHealthTracker channelHealthTracker;
    private final TokenService tokenService;
    private final UserService userService;
    private final UsageLogService usageLogService;

    /**
     * 构建仪表盘统计数据 - admin 看全局，普通用户看自己的数据
     */
    public DashboardStats buildDashboardStats(User currentUser) {
        if ("admin".equalsIgnoreCase(currentUser.getRole())) {
            return buildAdminStats();
        } else {
            return buildUserStats(currentUser);
        }
    }

    private DashboardStats buildAdminStats() {
        List<Channel> channels = channelService.listAll();
        long activeChannels = channels.stream().filter(c -> c.getStatus() == 1).count();
        long blockedChannels = channelHealthTracker.getBlockedChannelIds().size();

        return DashboardStats.builder()
                .totalChannels((long) channels.size())
                .activeChannels(activeChannels)
                .blockedChannels(blockedChannels)
                .totalTokens(tokenService.count())
                .totalUsers(userService.count())
                .totalRequests(usageLogService.getTotalRequests())
                .totalTokensUsed(channels.stream().mapToLong(Channel::getUsedQuota).sum())
                .requestsToday(usageLogService.getRequestsToday())
                .tokensUsedToday(usageLogService.getTokensUsedToday())
                .totalInputTokens(usageLogService.getTotalInputTokens())
                .totalOutputTokens(usageLogService.getTotalOutputTokens())
                .inputTokensToday(usageLogService.getInputTokensToday())
                .outputTokensToday(usageLogService.getOutputTokensToday())
                .totalCreditsConsumed(usageLogService.getTotalCreditsConsumed())
                .creditsConsumedToday(usageLogService.getCreditsConsumedToday())
                .build();
    }

    private DashboardStats buildUserStats(User currentUser) {
        // 只看自己的 Token 和使用记录（优化为 2 次聚合查询）
        List<Token> userTokens = tokenService.listByUser(currentUser.getId());
        List<Long> tokenIds = userTokens.stream().map(Token::getId).toList();

        long totalTokensUsed = userTokens.stream()
                .mapToLong(t -> t.getUsedQuota() != null ? t.getUsedQuota() : 0).sum();

        // 聚合查询：全部时间
        long totalRequests = 0, totalTokensUsedLog = 0, totalInputTokens = 0, totalOutputTokens = 0;
        double totalCreditsConsumed = 0;
        if (!tokenIds.isEmpty()) {
            Object[] allMetrics = usageLogService.sumAllMetricsByTokenIds(tokenIds);
            totalRequests = ((Number) allMetrics[0]).longValue();
            totalTokensUsedLog = ((Number) allMetrics[1]).longValue();
            totalInputTokens = ((Number) allMetrics[2]).longValue();
            totalOutputTokens = ((Number) allMetrics[3]).longValue();
            totalCreditsConsumed = ((Number) allMetrics[4]).doubleValue();
        }

        // 聚合查询：今日
        long requestsToday = 0, tokensUsedToday = 0, inputTokensToday = 0, outputTokensToday = 0;
        double creditsConsumedToday = 0;
        if (!tokenIds.isEmpty()) {
            Object[] todayMetrics = usageLogService.sumAllMetricsByTokenIdsSince(
                    tokenIds, LocalDate.now().atStartOfDay());
            requestsToday = ((Number) todayMetrics[0]).longValue();
            tokensUsedToday = ((Number) todayMetrics[1]).longValue();
            inputTokensToday = ((Number) todayMetrics[2]).longValue();
            outputTokensToday = ((Number) todayMetrics[3]).longValue();
            creditsConsumedToday = ((Number) todayMetrics[4]).doubleValue();
        }

        return DashboardStats.builder()
                .totalChannels(0L)
                .activeChannels(0L)
                .totalTokens((long) userTokens.size())
                .totalUsers(1L)
                .totalRequests(totalRequests)
                .totalTokensUsed(totalTokensUsed)
                .requestsToday(requestsToday)
                .tokensUsedToday(tokensUsedToday)
                .totalInputTokens(totalInputTokens)
                .totalOutputTokens(totalOutputTokens)
                .inputTokensToday(inputTokensToday)
                .outputTokensToday(outputTokensToday)
                .totalCreditsConsumed(totalCreditsConsumed)
                .creditsConsumedToday(creditsConsumedToday)
                .myCredits(currentUser.getCredits() != null ? currentUser.getCredits() : 0.0)
                .build();
    }
}
