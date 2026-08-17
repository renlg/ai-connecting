package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.dto.CostAggregateRow;
import com.aiconnecting.dto.CostSummary;
import com.aiconnecting.repository.CostQueryRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CostServiceTest {
    private final CostQueryRepository repository = mock(CostQueryRepository.class);
    private final CostService service = new CostService(repository);

    @Test
    void aggregateUsesInclusiveDateRangeAndBoundsPagination() {
        when(repository.findRows(any(), any(), eq(7L), eq("group-a"), eq(0), eq(200))).thenReturn(List.of());
        when(repository.summarize(any(), any(), any(), any())).thenReturn(CostSummary.builder().build());

        service.aggregate("2026-08-10", "2026-08-16", 7L, " group-a ", -2, 500);

        var range = service.parseRange("2026-08-10", "2026-08-16");
        assertThat(range.start()).isEqualTo(LocalDateTime.of(2026, 8, 10, 0, 0));
        assertThat(range.end()).isEqualTo(LocalDateTime.of(2026, 8, 16, 23, 59, 59, 999_000_000));
        verify(repository).countRows(range.start(), range.end(), 7L, "group-a");
    }

    @Test
    void rejectsInvalidOrReversedDates() {
        assertThatThrownBy(() -> service.aggregate("2026/08/10", "2026-08-16", null, null, 0, 20))
                .isInstanceOf(BusinessException.class).hasMessageContaining("YYYY-MM-DD");
        assertThatThrownBy(() -> service.aggregate("2026-08-17", "2026-08-16", null, null, 0, 20))
                .isInstanceOf(BusinessException.class).hasMessageContaining("开始日期");
        verifyNoInteractions(repository);
    }

    @Test
    void csvHasUtf8BomEscapingAllRowsAndFourDecimalCost() {
        CostAggregateRow row = CostAggregateRow.builder()
                .channelName("渠道,一").model("upstream\"A")
                .totalPromptTokens(10).totalCompletionTokens(2).requestCount(1)
                .modelType("image").imageCount(1).totalCreditCost(new BigDecimal("1.2")).build();
        when(repository.findRows(any(), any(), isNull(), isNull(), isNull(), isNull())).thenReturn(List.of(row));

        byte[] bytes = service.exportCsv("2026-08-10", "2026-08-16", null, null);
        String csv = new String(bytes, StandardCharsets.UTF_8);

        assertThat(bytes).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(csv).contains("\"渠道,一\"", "\"upstream\"\"A\"", "\"1.2000\"")
                .doesNotContain("实际上游模型");
    }

    @Test
    void modelOptionsUseParsedRangeAndChannel() {
        when(repository.findModelOptions(any(), any(), eq(7L))).thenReturn(List.of("upstream-a"));

        assertThat(service.modelOptions("2026-08-10", "2026-08-16", 7L)).containsExactly("upstream-a");

        var range = service.parseRange("2026-08-10", "2026-08-16");
        verify(repository).findModelOptions(range.start(), range.end(), 7L);
    }
}
