package com.vaultri.core.report.controller;

import com.vaultri.core.report.service.ReportService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
@Tag(name = "Reports", description = "Seller metrics, revenue, and protection reporting APIs")
public class ReportController {

    private final ReportService reportService;
}
