package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.common.CacheInvalidationService;
import com.aiconnecting.repository.ChannelRepository;
import com.aiconnecting.repository.ModelConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ModelConfigServiceTest {
    private ModelConfigRepository repository;
    private ModelConfigService service;

    @BeforeEach
    void setUp() {
        repository = mock(ModelConfigRepository.class);
        service = new ModelConfigService(repository, mock(ChannelRepository.class),
                mock(CacheInvalidationService.class));
    }

    @Test
    void duplicateNameUsesFriendlyBusinessError() {
        when(repository.existsByNameExcludingId("gpt-test", null)).thenReturn(true);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.validateNameUnique("gpt-test", null));

        assertEquals(400, error.getCode());
        assertEquals("模型名称已存在", error.getMessage());
    }

    @Test
    void updateNameCheckExcludesCurrentModel() {
        assertDoesNotThrow(() -> service.validateNameUnique("gpt-test", 5L));
        verify(repository).existsByNameExcludingId("gpt-test", 5L);
    }

    @Test
    void batchCreateRejectsDuplicateNamesBeforeInsert() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.batchCreate(List.of("gpt-test", "gpt-test")));

        assertEquals("模型名称已存在", error.getMessage());
        verify(repository, never()).saveAll(any());
    }
}
