package com.aiconnecting.controller;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.entity.ModelConfig;
import com.aiconnecting.repository.ModelConfigRepository;
import com.aiconnecting.service.ModelConfigService;
import com.aiconnecting.service.RelayService;
import com.aiconnecting.security.JwtAuthenticationFilter;
import com.aiconnecting.security.JwtUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ModelConfigController.class)
@AutoConfigureMockMvc(addFilters = false)
class ModelConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ModelConfigService modelConfigService;

    @MockBean
    private RelayService relayService;

    @MockBean
    private ModelConfigRepository modelConfigRepository;

    @MockBean
    private JwtUtils jwtUtils;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    // ==================== List ====================

    @Test
    void list() throws Exception {
        ModelConfig m1 = ModelConfig.builder().id(1L).name("gpt-4").status(1).build();
        ModelConfig m2 = ModelConfig.builder().id(2L).name("gpt-3.5").status(0).build();
        when(modelConfigService.listNonAdmin()).thenReturn(List.of(m1, m2));

        mockMvc.perform(get("/api/admin/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].name").value("gpt-4"));
    }

    @Test
    void list_empty() throws Exception {
        when(modelConfigService.listNonAdmin()).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    // ==================== ListEnabled ====================

    @Test
    void listEnabled() throws Exception {
        ModelConfig m = ModelConfig.builder().id(1L).name("gpt-4").status(1).build();
        when(modelConfigService.listEnabled(false)).thenReturn(List.of(m));

        mockMvc.perform(get("/api/admin/models/enabled"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].name").value("gpt-4"));
    }

    @Test
    void listEnabled_empty() throws Exception {
        when(modelConfigService.listEnabled(false)).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/models/enabled"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    // ==================== Create ====================

    @Test
    void create() throws Exception {
        ModelConfig saved = ModelConfig.builder().id(1L).name("gpt-4o")
                .displayName("GPT-4o").inputCreditRate(5).outputCreditRate(15).status(1).build();
        when(modelConfigService.save(any(ModelConfig.class))).thenReturn(saved);

        String body = """
                {
                    "name": "gpt-4o",
                    "displayName": "GPT-4o",
                    "inputCreditRate": 5,
                    "outputCreditRate": 15
                }
                """;

        mockMvc.perform(post("/api/admin/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("gpt-4o"))
                .andExpect(jsonPath("$.data.displayName").value("GPT-4o"));
    }

    @Test
    void create_missingName() throws Exception {
        mockMvc.perform(post("/api/admin/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"No name\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("模型名称不能为空"));
    }

    @Test
    void create_blankName() throws Exception {
        mockMvc.perform(post("/api/admin/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("模型名称不能为空"));
    }

    @Test
    void create_defaultRates() throws Exception {
        ModelConfig saved = ModelConfig.builder().id(1L).name("gpt-4o")
                .inputCreditRate(0).outputCreditRate(0).status(1).build();
        when(modelConfigService.save(any(ModelConfig.class))).thenReturn(saved);

        mockMvc.perform(post("/api/admin/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"gpt-4o\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inputCreditRate").value(0))
                .andExpect(jsonPath("$.data.outputCreditRate").value(0));
    }

    // ==================== Update ====================

    @Test
    void update() throws Exception {
        ModelConfig existing = ModelConfig.builder().id(1L).name("gpt-4")
                .displayName("GPT-4").inputCreditRate(5).outputCreditRate(15).status(1).build();
        when(modelConfigService.getById(1L)).thenReturn(existing);

        ModelConfig updated = ModelConfig.builder().id(1L).name("gpt-4")
                .displayName("GPT-4 Updated").inputCreditRate(10).outputCreditRate(20).status(1).build();
        when(modelConfigService.save(any(ModelConfig.class))).thenReturn(updated);

        String body = """
                {
                    "displayName": "GPT-4 Updated",
                    "inputCreditRate": 10,
                    "outputCreditRate": 20
                }
                """;

        mockMvc.perform(put("/api/admin/models/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("GPT-4 Updated"));
    }

    @Test
    void update_notFound() throws Exception {
        when(modelConfigService.getById(99L)).thenThrow(new BusinessException("模型不存在"));

        mockMvc.perform(put("/api/admin/models/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"test\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("模型不存在"));
    }

    // ==================== Delete ====================

    @Test
    void deleteModel() throws Exception {
        when(modelConfigService.existsById(1L)).thenReturn(true);
        doNothing().when(modelConfigService).delete(1L);

        mockMvc.perform(delete("/api/admin/models/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void deleteModel_notFound() throws Exception {
        when(modelConfigService.existsById(99L)).thenReturn(false);

        mockMvc.perform(delete("/api/admin/models/99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("模型不存在"));
    }

    // ==================== UpdateStatus ====================

    @Test
    void updateStatus() throws Exception {
        ModelConfig config = ModelConfig.builder().id(1L).name("gpt-4").status(0).build();
        when(modelConfigService.getById(1L)).thenReturn(config);
        when(modelConfigService.save(any(ModelConfig.class))).thenReturn(config);

        mockMvc.perform(put("/api/admin/models/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void updateStatus_notFound() throws Exception {
        when(modelConfigService.getById(99L)).thenThrow(new BusinessException("模型不存在"));

        mockMvc.perform(put("/api/admin/models/99/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("模型不存在"));
    }

    // ==================== BatchCreate ====================

    @Test
    void batchCreate() throws Exception {
        ModelConfig m1 = ModelConfig.builder().id(1L).name("gpt-4o").status(1).build();
        ModelConfig m2 = ModelConfig.builder().id(2L).name("gpt-4o-mini").status(1).build();
        when(modelConfigService.batchCreate(List.of("gpt-4o", "gpt-4o-mini")))
                .thenReturn(List.of(m1, m2));

        String body = """
                {
                    "names": ["gpt-4o", "gpt-4o-mini"]
                }
                """;

        mockMvc.perform(post("/api/admin/models/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void batchCreate_emptyNames() throws Exception {
        String body = """
                {
                    "names": []
                }
                """;

        mockMvc.perform(post("/api/admin/models/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("模型名称列表不能为空"));
    }

    @Test
    void batchCreate_nullNames() throws Exception {
        mockMvc.perform(post("/api/admin/models/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("模型名称列表不能为空"));
    }

    @Test
    void batchCreate_filtersBlankNames() throws Exception {
        ModelConfig m = ModelConfig.builder().id(1L).name("gpt-4o").status(1).build();
        when(modelConfigService.batchCreate(List.of("gpt-4o", "", "  "))).thenReturn(List.of(m));

        String body = """
                {
                    "names": ["gpt-4o", "", "  "]
                }
                """;

        mockMvc.perform(post("/api/admin/models/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1));
    }
}
