package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.dto.ChannelRequest;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.entity.ModelConfig;
import com.aiconnecting.repository.ChannelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChannelServiceTest {
    private ChannelRepository repository;
    private ModelConfigService modelConfigService;
    private ChannelService service;
    private ModelConfig model;

    @BeforeEach
    void setUp() {
        repository = mock(ChannelRepository.class);
        modelConfigService = mock(ModelConfigService.class);
        service = new ChannelService(repository);
        ReflectionTestUtils.setField(service, "modelConfigService", modelConfigService);
        model = ModelConfig.builder().id(7L).name("platform-model").type("text").build();
        when(repository.findChannelsByModel(anyString())).thenReturn(List.of());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(modelConfigService.getById(7L)).thenReturn(model);
        when(modelConfigService.findByName("platform-model")).thenReturn(List.of(model));
    }

    @Test
    void customRejectsMissingInvalidEmptyUnknownAndModelIdMismatchMappings() {
        assertThrows(BusinessException.class, () -> service.create(request("custom", "7", null)));
        assertThrows(BusinessException.class, () -> service.create(request("custom", "7", "not-json")));
        assertThrows(BusinessException.class, () -> service.create(request("custom", "7", "{\"platform-model\":\"\"}")));
        assertThrows(BusinessException.class, () -> service.create(request("custom", "7", "{\"unknown\":\"up\"}")));
        assertThrows(BusinessException.class, () -> service.create(request("custom", "7", "{}")));
    }

    @Test
    void normalChannelAllowsNullMapping() {
        assertDoesNotThrow(() -> service.create(request("openai", "7", null)));
    }

    @Test
    void createRejectsDuplicateNameWithFriendlyBusinessError() {
        when(repository.existsByNameExcludingId("test", null)).thenReturn(true);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create(request("openai", "7", null)));

        assertEquals(400, error.getCode());
        assertEquals("渠道名称已存在", error.getMessage());
        verify(repository, never()).save(any());
    }

    @Test
    void updateNameCheckExcludesCurrentChannel() {
        Channel existing = Channel.builder().id(3L).name("test").type("openai").modelIds("7").build();
        when(repository.findById(3L)).thenReturn(java.util.Optional.of(existing));

        assertDoesNotThrow(() -> service.update(3L, request("openai", "7", null)));

        verify(repository).existsByNameExcludingId("test", 3L);
    }

    @Test
    void sameModelCannotBeBoundToCustomAndNormalChannels() {
        when(repository.findChannelsByModel("%,7,%")).thenReturn(List.of(Channel.builder()
                .id(2L).type("openai").modelIds("7").build()));
        assertThrows(BusinessException.class, () -> service.create(
                request("custom", "7", "{\"platform-model\":\"upstream\"}")));
    }

    @Test
    void validCustomMappingIsStored() {
        Channel saved = service.create(request("custom", "7", "{\"platform-model\":\"upstream\"}"));
        assertEquals("{\"platform-model\":\"upstream\"}", saved.getModelMapping());
    }

    @Test
    void customRejectsMediaModelButAcceptsTextModel() {
        model.setType("image");
        BusinessException error = assertThrows(BusinessException.class, () -> service.create(
                request("custom", "7", "{\"platform-model\":\"upstream\"}")));
        assertEquals("custom 透传渠道仅支持文本模型", error.getMessage());

        model.setType("text");
        assertDoesNotThrow(() -> service.create(
                request("custom", "7", "{\"platform-model\":\"upstream\"}")));
    }

    @Test
    void passthroughOnlyLookupUsesTargetedQueryAndNeverFindAll() {
        when(repository.findChannelsByModel("%,7,%")).thenReturn(List.of(
                Channel.builder().id(1L).type("custom").modelIds("7").build()));

        assertTrue(service.isPassthroughOnlyModel("7"));

        verify(repository).findChannelsByModel("%,7,%");
        verify(repository, never()).findAll();
    }

    private ChannelRequest request(String type, String modelIds, String mapping) {
        ChannelRequest request = new ChannelRequest();
        request.setName("test");
        request.setType(type);
        request.setBaseUrl("https://example.com");
        request.setApiKey("key");
        request.setModelIds(modelIds);
        request.setModelMapping(mapping);
        return request;
    }
}
