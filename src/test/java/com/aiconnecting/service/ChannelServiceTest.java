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
        model = ModelConfig.builder().id(7L).name("platform-model").build();
        when(repository.findAll()).thenReturn(List.of());
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
    void sameModelCannotBeBoundToCustomAndNormalChannels() {
        when(repository.findAll()).thenReturn(List.of(Channel.builder()
                .id(2L).type("openai").modelIds("7").build()));
        assertThrows(BusinessException.class, () -> service.create(
                request("custom", "7", "{\"platform-model\":\"upstream\"}")));
    }

    @Test
    void validCustomMappingIsStored() {
        Channel saved = service.create(request("custom", "7", "{\"platform-model\":\"upstream\"}"));
        assertEquals("{\"platform-model\":\"upstream\"}", saved.getModelMapping());
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
