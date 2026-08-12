package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.entity.ModelGroup;
import com.aiconnecting.entity.Token;
import com.aiconnecting.entity.User;
import com.aiconnecting.repository.VideoTaskRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelGroupFailoverExecutorTest {

    @Test
    void directGroupRequest_returnsModelNotFoundForUnsupportedUserLevel() {
        RelaySupport support = mock(RelaySupport.class);
        ModelGroupService groupService = mock(ModelGroupService.class);
        ModelGroupRoutingService routingService = mock(ModelGroupRoutingService.class);
        ModelGroup group = ModelGroup.builder()
                .id(10L).name("bailian-good").type("text").enabled(true)
                .supportedLevels("2,3,4,5").build();
        User user = User.builder().id(7L).role("user").level(1).build();
        Token token = Token.builder().id(8L).userId(7L).build();

        when(groupService.findByName("bailian-good")).thenReturn(Optional.of(group));
        when(support.prepareGroupContext("sk-level-one", "bailian-good"))
                .thenReturn(new RelaySupport.RelayContext(token, null, 1, user, null));

        ModelGroupFailoverExecutor executor = new ModelGroupFailoverExecutor(
                support, groupService, routingService, mock(ModelGroupBillingService.class),
                mock(ModelHealthTracker.class), mock(UsageLogService.class),
                mock(VideoTaskRepository.class), mock(VideoTaskUsageLogService.class));

        assertThatThrownBy(() -> executor.relayRequest(
                "sk-level-one", "/v1/chat/completions", "{\"model\":\"bailian-good\"}",
                "bailian-good", null))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(404);
                    assertThat(error.getEnglishMessage())
                            .isEqualTo("Model group not found or disabled: bailian-good");
                    assertThat(error.isUpstreamResponse()).isFalse();
                });
        verify(routingService, never()).resolveOrderedCandidates(group, false, 1);
    }
}
