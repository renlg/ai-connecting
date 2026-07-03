package com.aiconnecting.controller;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.dto.ChannelRequest;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.service.ChannelService;
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

@WebMvcTest(ChannelController.class)
@AutoConfigureMockMvc(addFilters = false)
class ChannelControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ChannelService channelService;

    @MockBean
    private JwtUtils jwtUtils;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    // ==================== List ====================

    @Test
    void list() throws Exception {
        Channel ch = Channel.builder().id(1L).name("OpenAI").type("openai").status(1).build();
        when(channelService.listAll()).thenReturn(List.of(ch));

        mockMvc.perform(get("/api/admin/channels"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].name").value("OpenAI"));
    }

    @Test
    void list_empty() throws Exception {
        when(channelService.listAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/channels"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    // ==================== GetById ====================

    @Test
    void getById() throws Exception {
        Channel ch = Channel.builder().id(1L).name("OpenAI").type("openai").status(1).build();
        when(channelService.getById(1L)).thenReturn(ch);

        mockMvc.perform(get("/api/admin/channels/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("OpenAI"));
    }

    @Test
    void getById_notFound() throws Exception {
        when(channelService.getById(99L)).thenThrow(new BusinessException("渠道不存在"));

        mockMvc.perform(get("/api/admin/channels/99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("渠道不存在"));
    }

    // ==================== Create ====================

    @Test
    void create() throws Exception {
        Channel ch = Channel.builder().id(1L).name("OpenAI").type("openai")
                .baseUrl("https://api.openai.com").apiKey("sk-xxx").status(1).build();
        when(channelService.create(any(ChannelRequest.class))).thenReturn(ch);

        String body = """
                {
                    "name": "OpenAI",
                    "type": "openai",
                    "baseUrl": "https://api.openai.com",
                    "apiKey": "sk-xxx"
                }
                """;

        mockMvc.perform(post("/api/admin/channels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("OpenAI"));
    }

    // ==================== Update ====================

    @Test
    void update() throws Exception {
        Channel ch = Channel.builder().id(1L).name("OpenAI-Updated").type("openai").status(1).build();
        when(channelService.update(eq(1L), any(ChannelRequest.class))).thenReturn(ch);

        String body = """
                {
                    "name": "OpenAI-Updated"
                }
                """;

        mockMvc.perform(put("/api/admin/channels/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("OpenAI-Updated"));
    }

    @Test
    void update_notFound() throws Exception {
        when(channelService.update(eq(99L), any(ChannelRequest.class)))
                .thenThrow(new BusinessException("渠道不存在"));

        mockMvc.perform(put("/api/admin/channels/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"test\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("渠道不存在"));
    }

    // ==================== Delete ====================

    @Test
    void deleteChannel() throws Exception {
        doNothing().when(channelService).delete(1L);

        mockMvc.perform(delete("/api/admin/channels/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void deleteChannel_notFound() throws Exception {
        doThrow(new BusinessException("渠道不存在")).when(channelService).delete(99L);

        mockMvc.perform(delete("/api/admin/channels/99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("渠道不存在"));
    }

    // ==================== UpdateStatus ====================

    @Test
    void updateStatus() throws Exception {
        doNothing().when(channelService).updateStatus(eq(1L), eq(0));

        mockMvc.perform(put("/api/admin/channels/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // ==================== Test ====================

    @Test
    void testChannel_success() throws Exception {
        when(channelService.testChannel(1L)).thenReturn(true);

        mockMvc.perform(post("/api/admin/channels/1/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    void testChannel_fail() throws Exception {
        when(channelService.testChannel(1L)).thenReturn(false);

        mockMvc.perform(post("/api/admin/channels/1/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(false));
    }

    @Test
    void testChannel_notFound() throws Exception {
        when(channelService.testChannel(99L)).thenThrow(new BusinessException("渠道不存在"));

        mockMvc.perform(post("/api/admin/channels/99/test"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("渠道不存在"));
    }
}
