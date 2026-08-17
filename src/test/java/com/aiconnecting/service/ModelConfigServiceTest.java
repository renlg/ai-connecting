package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.common.CacheInvalidationService;
import com.aiconnecting.common.DuplicateSubmitGuard;
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
    private DuplicateSubmitGuard duplicateSubmitGuard;

    @BeforeEach
    void setUp() {
        repository = mock(ModelConfigRepository.class);
        duplicateSubmitGuard = mock(DuplicateSubmitGuard.class);
        when(duplicateSubmitGuard.tryAcquire(anyString(), anyString())).thenReturn(true);
        service = new ModelConfigService(repository, mock(ChannelRepository.class),
                mock(CacheInvalidationService.class), duplicateSubmitGuard);
    }

    @Test
    void duplicateDisplayNameUsesFriendlyBusinessError() {
        when(duplicateSubmitGuard.tryAcquire("model", "GPT Test")).thenReturn(false);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.guardDuplicateDisplayName("GPT Test"));

        assertEquals(400, error.getCode());
        assertEquals("显示名称 \"GPT Test\" 已存在，请使用唯一的显示名称", error.getMessage());
    }

    @Test
    void updateUsesSubmittedDisplayNameAsDedupIdentifier() {
        assertDoesNotThrow(() -> service.guardDuplicateDisplayName("GPT Test"));
        verify(duplicateSubmitGuard).tryAcquire("model", "GPT Test");
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
