package com.aiconnecting.service;

import com.aiconnecting.entity.ModelConfig;
import com.aiconnecting.entity.ModelGroup;
import com.aiconnecting.entity.ModelGroupMember;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelGroupRoutingServiceTest {

    @Test
    void resolveOrderedCandidates_excludesMembersUnsupportedForUserLevel() {
        ModelGroupService groupService = mock(ModelGroupService.class);
        ModelGroupRoutingService routingService = new ModelGroupRoutingService(groupService);
        ModelGroup group = ModelGroup.builder().id(10L).strategy("priority").build();

        ModelConfig restricted = ModelConfig.builder()
                .id(1L).name("level-two-model").status(1).supportedLevels("2,3,4,5").build();
        ModelConfig open = ModelConfig.builder()
                .id(2L).name("open-model").status(1).supportedLevels("").build();
        ModelConfig levelOne = ModelConfig.builder()
                .id(3L).name("level-one-model").status(1).supportedLevels("1,2").build();

        when(groupService.listMemberViews(10L)).thenReturn(List.of(
                memberView(1L, restricted), memberView(2L, open), memberView(3L, levelOne)));

        List<ModelGroupRoutingService.Candidate> candidates =
                routingService.resolveOrderedCandidates(group, false, 1);

        assertThat(candidates).extracting(candidate -> candidate.modelConfig().getName())
                .containsExactly("open-model", "level-one-model");
    }

    @Test
    void resolveOrderedCandidates_prefersVisionByRequestWithoutFilteringFallbackMembers() {
        ModelGroupService groupService = mock(ModelGroupService.class);
        ModelGroupRoutingService routingService = new ModelGroupRoutingService(groupService);
        ModelGroup group = ModelGroup.builder().id(10L).strategy("priority").build();

        ModelConfig textFirst = ModelConfig.builder()
                .id(1L).name("text-first").status(1).visionSupport(false).build();
        ModelConfig vision = ModelConfig.builder()
                .id(2L).name("vision").status(1).visionSupport(true).build();
        ModelConfig textLast = ModelConfig.builder()
                .id(3L).name("text-last").status(1).visionSupport(false).build();
        when(groupService.listMemberViews(10L)).thenReturn(List.of(
                memberView(1L, textFirst), memberView(2L, vision), memberView(3L, textLast)));

        assertThat(routingService.resolveOrderedCandidates(group, false, 1, true))
                .extracting(candidate -> candidate.modelConfig().getName())
                .containsExactly("vision", "text-first", "text-last");
        assertThat(routingService.resolveOrderedCandidates(group, false, 1, false))
                .extracting(candidate -> candidate.modelConfig().getName())
                .containsExactly("text-first", "text-last", "vision");
    }

    private ModelGroupService.MemberView memberView(Long id, ModelConfig config) {
        ModelGroupMember member = ModelGroupMember.builder()
                .id(id).groupId(10L).modelConfigId(config.getId()).sortOrder(id.intValue()).weight(1).build();
        return new ModelGroupService.MemberView(member, config);
    }
}
