package com.vaultri.core.report.controller;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaultri.core.auth.security.CurrentSeller;
import com.vaultri.core.report.dto.DateRange;
import com.vaultri.core.report.dto.MonthlyDealsReport;
import com.vaultri.core.report.dto.RemindersReportResponse;
import com.vaultri.core.report.dto.SummaryReportResponse;
import com.vaultri.core.report.service.ReportCacheService;
import com.vaultri.core.report.service.ReportingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
@Tag(name = "Reports", description = "Seller reporting and analytics APIs")
public class ReportController {

    private final ReportingService reportingService;
    private final ReportCacheService reportCacheService;
    private final ObjectMapper objectMapper;

    @GetMapping("/summary")
    @Operation(summary = "Get deals summary report", description = "Aggregates deal statuses, price, deposits, and cancellation fees in range")
    public ResponseEntity<SummaryReportResponse> getSummary(
            @CurrentSeller UUID sellerId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        DateRange range = DateRange.parseAndValidate(from, to);
        String fromKey = range.fromDate().toString();
        String toKey = range.toDate().toString();

        SummaryReportResponse response = reportCacheService.getOrCompute(
                "summary", sellerId, fromKey, toKey, SummaryReportResponse.class, null,
                () -> reportingService.getSummary(sellerId, range.start(), range.end())
        );
        log.info("Generated summary report for seller {}: {} total deals in range", sellerId, response.totalDeals());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/deals-by-month")
    @Operation(summary = "Get deals by month report", description = "Monthly breakdown in UTC of created, completed, and cancelled deals and deposits")
    public ResponseEntity<List<MonthlyDealsReport>> getDealsByMonth(
            @CurrentSeller UUID sellerId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        DateRange range = DateRange.parseAndValidate(from, to);
        String fromKey = range.fromDate().toString();
        String toKey = range.toDate().toString();

        JavaType listType = objectMapper.getTypeFactory().constructCollectionType(List.class, MonthlyDealsReport.class);
        List<MonthlyDealsReport> response = reportCacheService.getOrCompute(
                "deals-by-month", sellerId, fromKey, toKey, null, listType,
                () -> reportingService.getDealsByMonth(sellerId, range.start(), range.end())
        );
        log.info("Generated deals-by-month report for seller {}: {} months", sellerId, response.size());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/reminders")
    @Operation(summary = "Get reminders report", description = "Counts of reminders by status and kind for the seller's deals")
    public ResponseEntity<RemindersReportResponse> getReminders(
            @CurrentSeller UUID sellerId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        String fromKey = "all";
        String toKey = "all";
        if ((from != null && !from.isBlank()) || (to != null && !to.isBlank())) {
            DateRange range = DateRange.parseAndValidate(from, to);
            fromKey = range.fromDate().toString();
            toKey = range.toDate().toString();
        }

        RemindersReportResponse response = reportCacheService.getOrCompute(
                "reminders", sellerId, fromKey, toKey, RemindersReportResponse.class, null,
                () -> reportingService.getReminders(sellerId)
        );
        log.info("Generated reminders report for seller {}: {} total reminders", sellerId, response.total());
        return ResponseEntity.ok(response);
    }
}
