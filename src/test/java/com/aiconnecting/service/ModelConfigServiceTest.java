package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.common.CacheInvalidationService;
import com.aiconnecting.entity.ModelConfig;
import com.aiconnecting.repository.ChannelRepository;
import com.aiconnecting.repository.ModelConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    void duplicateDisplayNameUsesFriendlyBusinessError() {
        when(repository.existsByDisplayNameExcludingId("GPT Test", null)).thenReturn(true);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.validateDisplayNameUnique("GPT Test", null));

        assertEquals(400, error.getCode());
        assertEquals("显示名称 \"GPT Test\" 已存在，请使用唯一的显示名称", error.getMessage());
    }

    @Test
    void updateDisplayNameCheckExcludesCurrentModel() {
        assertDoesNotThrow(() -> service.validateDisplayNameUnique("GPT Test", 5L));
        verify(repository).existsByDisplayNameExcludingId("GPT Test", 5L);
    }

    @Test
    void createAllowsDuplicateNamesWithDifferentDisplayNames() {
        when(repository.save(any(ModelConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ModelConfig first = ModelConfig.builder().name("gpt-test").displayName("GPT Test A").build();
        ModelConfig second = ModelConfig.builder().name("gpt-test").displayName("GPT Test B").build();

        assertDoesNotThrow(() -> service.save(first));
        assertDoesNotThrow(() -> service.save(second));
        verify(repository, times(2)).save(any(ModelConfig.class));
    }
}
