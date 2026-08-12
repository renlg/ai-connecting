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

    private ModelGroupService.MemberView memberView(Long id, ModelConfig config) {
        ModelGroupMember member = ModelGroupMember.builder()
                .id(id).groupId(10L).modelConfigId(config.getId()).sortOrder(id.intValue()).weight(1).build();
        return new ModelGroupService.MemberView(member, config);
    }
}
