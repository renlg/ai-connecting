package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.common.CacheInvalidationService;
import com.aiconnecting.common.DuplicateSubmitGuard;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.entity.ModelConfig;
import com.aiconnecting.repository.ChannelRepository;
import com.aiconnecting.repository.ModelConfigRepository;
import com.aiconnecting.repository.ModelGroupMemberRepository;
import com.aiconnecting.repository.ModelGroupRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ModelGroupServiceTest {

    @Test
    void duplicateNameIsRejectedAndSubmittedNameIsUsedAsDedupIdentifier() {
        ModelGroupRepository groups = mock(ModelGroupRepository.class);
        DuplicateSubmitGuard duplicateSubmitGuard = mock(DuplicateSubmitGuard.class);
        ModelGroupService service = new ModelGroupService(groups, mock(ModelGroupMemberRepository.class),
                mock(ModelConfigRepository.class), mock(ChannelRepository.class),
                mock(CacheInvalidationService.class), duplicateSubmitGuard);
        when(duplicateSubmitGuard.tryAcquire("model-group", "shared")).thenReturn(false, true);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.guardDuplicateName("shared"));

        assertEquals(400, error.getCode());
        assertEquals("模型组名称 \"shared\" 已存在", error.getMessage());
        service.guardDuplicateName("shared");
        verify(duplicateSubmitGuard, times(2)).tryAcquire("model-group", "shared");
    }

    @Test
    void mediaMemberBoundToCustomChannelIsRejected() {
        ModelGroupRepository groups = mock(ModelGroupRepository.class);
        ModelGroupMemberRepository members = mock(ModelGroupMemberRepository.class);
        ModelConfigRepository models = mock(ModelConfigRepository.class);
        ChannelRepository channels = mock(ChannelRepository.class);
        ModelGroupService service = new ModelGroupService(groups, members, models, channels,
                mock(CacheInvalidationService.class), mock(DuplicateSubmitGuard.class));
        ModelConfig image = ModelConfig.builder().id(7L).name("image-model").type("image").build();
        when(models.findById(7L)).thenReturn(Optional.of(image));
        when(channels.findChannelsByModel("%,7,%")).thenReturn(List.of(
                Channel.builder().id(9L).type("custom").modelIds("7").build()));

        BusinessException error = assertThrows(BusinessException.class, () -> service.replaceMembers(
                1L, "image", List.of(new ModelGroupService.MemberInput(7L, 1))));

        assertEquals("custom 透传渠道仅支持文本模型", error.getMessage());
        verify(members, never()).save(any());
    }
}
