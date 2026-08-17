package com.aiconnecting.controller;

import com.aiconnecting.common.ApiResponse;
import com.aiconnecting.dto.CostAggregateResponse;
import com.aiconnecting.service.CostService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/admin/cost")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class CostController {
    private final CostService costService;

    @GetMapping("/aggregate")
    public ApiResponse<CostAggregateResponse> aggregate(
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(required = false) Long channelId,
            @RequestParam(required = false) String modelName,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(costService.aggregate(startDate, endDate, channelId, modelName, page, size));
    }

    @GetMapping(value = "/export", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> export(
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(required = false) Long channelId,
            @RequestParam(required = false) String modelName) {
        byte[] csv = costService.exportCsv(startDate, endDate, channelId, modelName);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename("cost-" + startDate + "-to-" + endDate + ".csv", StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }
}
