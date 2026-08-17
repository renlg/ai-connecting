package com.aiconnecting.controller;

import com.aiconnecting.dto.CostAggregateResponse;
import com.aiconnecting.service.CostService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CostControllerTest {
    private final CostService service = mock(CostService.class);
    private final CostController controller = new CostController(service);

    @Test
    void bothEndpointsAreProtectedByAdminRoleAtControllerLevel() {
        PreAuthorize rule = CostController.class.getAnnotation(PreAuthorize.class);
        assertThat(rule).isNotNull();
        assertThat(rule.value()).isEqualTo("hasRole('ADMIN')");
    }

    @Test
    void aggregateReturnsStandardApiEnvelope() {
        CostAggregateResponse response = CostAggregateResponse.builder().totalElements(3).page(1).size(10).build();
        when(service.aggregate("2026-08-10", "2026-08-16", 2L, "model-a", 1, 10)).thenReturn(response);

        var result = controller.aggregate("2026-08-10", "2026-08-16", 2L, "model-a", 1, 10);

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).isSameAs(response);
    }

    @Test
    void modelsReturnsStandardApiEnvelope() {
        when(service.modelOptions("2026-08-10", "2026-08-16", 2L)).thenReturn(List.of("upstream-a"));

        var result = controller.models("2026-08-10", "2026-08-16", 2L);

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).containsExactly("upstream-a");
    }

    @Test
    void exportHasCsvCharsetAttachmentAndBomBody() {
        byte[] bytes = "\uFEFF渠道,成本\r\n".getBytes(StandardCharsets.UTF_8);
        when(service.exportCsv("2026-08-10", "2026-08-16", null, null)).thenReturn(bytes);

        var response = controller.export("2026-08-10", "2026-08-16", null, null);

        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("text/csv;charset=UTF-8");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment", "cost-2026-08-10-to-2026-08-16.csv");
        assertThat(response.getBody()).isEqualTo(bytes);
    }
}
