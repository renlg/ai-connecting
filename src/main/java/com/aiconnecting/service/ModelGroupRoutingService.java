package com.aiconnecting.service;

import com.aiconnecting.common.LevelUtils;
import com.aiconnecting.entity.ModelConfig;
import com.aiconnecting.entity.ModelGroup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模型组成员路由：按组的 strategy 计算成员尝试顺序，过滤禁用/不满足权限的成员。
 * 与 {@link ChannelRouter}（渠道级路由）相互独立，只负责"组 -> 成员模型"这一层。
 */
@Service
@RequiredArgsConstructor
public class ModelGroupRoutingService {

    private final ModelGroupService modelGroupService;

    private final ConcurrentHashMap<Long, AtomicInteger> roundRobinCursors = new ConcurrentHashMap<>();

    public record Candidate(ModelConfig modelConfig, String channelModelId) {}

    /**
     * 返回该组按策略排序后的可用成员候选列表（已过滤禁用模型；非管理员额外过滤 adminOnly
     * 以及 supportedLevels 不包含当前用户等级的成员）。故障转移场景下不因转入组而向普通用户
     * 暴露无权使用的成员模型。
     */
    public List<Candidate> resolveOrderedCandidates(ModelGroup group, boolean isAdmin, Integer userLevel) {
        return resolveOrderedCandidates(group, isAdmin, userLevel, null);
    }

    /**
     * 在原成员策略顺序之上应用视觉能力偏好。偏好只做稳定分区，不过滤任何成员：带图请求把
     * visionSupport=true 放在前面，纯文本请求反之，同一分区内仍保持 priority/round-robin/random
     * 生成的原始顺序。
     */
    public List<Candidate> resolveOrderedCandidates(ModelGroup group, boolean isAdmin, Integer userLevel,
                                                    Boolean requestHasImage) {
        List<ModelGroupService.MemberView> views = modelGroupService.listMemberViews(group.getId());
        List<ModelGroupService.MemberView> eligible = views.stream()
                .filter(v -> v.modelConfig().getStatus() != null && v.modelConfig().getStatus() == 1)
                .filter(v -> isAdmin || !Boolean.TRUE.equals(v.modelConfig().getAdminOnly()))
                .filter(v -> isAdmin || LevelUtils.isAllowed(v.modelConfig().getSupportedLevels(), userLevel))
                .toList();
        if (eligible.isEmpty()) {
            return List.of();
        }
        List<ModelGroupService.MemberView> ordered = switch (group.getStrategy()) {
            case "priority" -> eligible;
            case "random" -> weightedShuffle(eligible);
            default -> rotate(group.getId(), eligible);
        };
        if (requestHasImage != null && ordered.size() > 1) {
            boolean preferVision = requestHasImage;
            List<ModelGroupService.MemberView> preferred = ordered.stream()
                    .filter(v -> Boolean.TRUE.equals(v.modelConfig().getVisionSupport()) == preferVision)
                    .toList();
            List<ModelGroupService.MemberView> fallback = ordered.stream()
                    .filter(v -> Boolean.TRUE.equals(v.modelConfig().getVisionSupport()) != preferVision)
                    .toList();
            List<ModelGroupService.MemberView> partitioned = new ArrayList<>(ordered.size());
            partitioned.addAll(preferred);
            partitioned.addAll(fallback);
            ordered = partitioned;
        }
        List<Candidate> result = new ArrayList<>(ordered.size());
        for (ModelGroupService.MemberView v : ordered) {
            result.add(new Candidate(v.modelConfig(), String.valueOf(v.modelConfig().getId())));
        }
        return result;
    }

    /** 该组是否至少有一个可用（启用状态）成员，供 /v1/models 与 token 模型列表判断组是否可展示 */
    public boolean hasAvailableMember(ModelGroup group, boolean isAdmin, Integer userLevel) {
        return !resolveOrderedCandidates(group, isAdmin, userLevel).isEmpty();
    }

    private List<ModelGroupService.MemberView> rotate(Long groupId, List<ModelGroupService.MemberView> members) {
        if (members.size() <= 1) {
            return members;
        }
        AtomicInteger cursor = roundRobinCursors.computeIfAbsent(groupId, id -> new AtomicInteger(0));
        int start = Math.floorMod(cursor.getAndIncrement(), members.size());
        List<ModelGroupService.MemberView> rotated = new ArrayList<>(members.size());
        for (int i = 0; i < members.size(); i++) {
            rotated.add(members.get((start + i) % members.size()));
        }
        return rotated;
    }

    private List<ModelGroupService.MemberView> weightedShuffle(List<ModelGroupService.MemberView> members) {
        List<ModelGroupService.MemberView> pool = new ArrayList<>(members);
        List<ModelGroupService.MemberView> result = new ArrayList<>(members.size());
        ThreadLocalRandom random = ThreadLocalRandom.current();
        while (!pool.isEmpty()) {
            int totalWeight = pool.stream()
                    .mapToInt(v -> Math.max(v.member().getWeight() != null ? v.member().getWeight() : 1, 1))
                    .sum();
            int pick = random.nextInt(totalWeight);
            int cumulative = 0;
            int selectedIndex = pool.size() - 1;
            for (int i = 0; i < pool.size(); i++) {
                int w = Math.max(pool.get(i).member().getWeight() != null ? pool.get(i).member().getWeight() : 1, 1);
                cumulative += w;
                if (pick < cumulative) {
                    selectedIndex = i;
                    break;
                }
            }
            result.add(pool.remove(selectedIndex));
        }
        return result;
    }
}
