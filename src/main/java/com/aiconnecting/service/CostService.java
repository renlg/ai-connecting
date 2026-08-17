package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.dto.CostAggregateResponse;
import com.aiconnecting.dto.CostAggregateRow;
import com.aiconnecting.dto.CostSummary;
import com.aiconnecting.repository.CostQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CostService {
    private static final int MAX_PAGE_SIZE = 200;

    private final CostQueryRepository repository;

    @Transactional(readOnly = true)
    public CostAggregateResponse aggregate(String startDate, String endDate, Long channelId,
                                           String modelName, int page, int size) {
        DateRange range = parseRange(startDate, endDate);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        List<CostAggregateRow> rows = repository.findRows(range.start(), range.end(), channelId,
                normalize(modelName), safePage, safeSize);
        return CostAggregateResponse.builder()
                .content(rows)
                .totalElements(repository.countRows(range.start(), range.end(), channelId, normalize(modelName)))
                .page(safePage)
                .size(safeSize)
                .summary(repository.summarize(range.start(), range.end(), channelId, normalize(modelName)))
                .build();
    }

    @Transactional(readOnly = true)
    public byte[] exportCsv(String startDate, String endDate, Long channelId, String modelName) {
        DateRange range = parseRange(startDate, endDate);
        List<CostAggregateRow> rows = repository.findRows(range.start(), range.end(), channelId,
                normalize(modelName), null, null);
        StringBuilder csv = new StringBuilder("\uFEFF")
                .append("渠道,模型,实际上游模型,输入token,输出token,缓存创建token,缓存读取token,张数,秒数,请求数,成本(积分/美元)\r\n");
        for (CostAggregateRow row : rows) {
            appendCsv(csv, row.getChannelName());
            appendCsv(csv, row.getModel());
            appendCsv(csv, row.getActualModel());
            appendCsv(csv, row.getTotalPromptTokens());
            appendCsv(csv, row.getTotalCompletionTokens());
            appendCsv(csv, row.getTotalCacheCreation());
            appendCsv(csv, row.getTotalCacheRead());
            appendCsv(csv, "image".equals(row.getModelType()) ? row.getImageCount() : "");
            appendCsv(csv, "video".equals(row.getModelType()) ? row.getVideoSeconds() : "");
            appendCsv(csv, row.getRequestCount());
            appendCsv(csv, scale(row.getTotalCreditCost()), true);
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    DateRange parseRange(String startDate, String endDate) {
        try {
            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);
            if (start.isAfter(end)) throw new BusinessException("开始日期不能晚于结束日期");
            return new DateRange(start.atStartOfDay(), end.atTime(23, 59, 59, 999_000_000));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("日期格式无效，请使用 YYYY-MM-DD");
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String scale(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(4, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static void appendCsv(StringBuilder csv, Object value) {
        appendCsv(csv, value, false);
    }

    private static void appendCsv(StringBuilder csv, Object value, boolean last) {
        String text = value == null ? "" : String.valueOf(value);
        csv.append('"').append(text.replace("\"", "\"\"")).append('"').append(last ? "\r\n" : ",");
    }

    /** 北京时区的含首尾日期边界；repository 按数据库方言转换绑定类型。 */
    record DateRange(LocalDateTime start, LocalDateTime end) {}
}
