package com.vaultri.core.audit.controller;

import com.vaultri.core.audit.dto.AuditLogResponse;
import com.vaultri.core.audit.entity.AuditLog;
import com.vaultri.core.audit.repository.AuditRepository;
import com.vaultri.core.auth.security.CurrentSeller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/audit-logs")
@RequiredArgsConstructor
@Tag(name = "Audit Logs", description = "Seller and deal immutable audit trail APIs")
public class AuditController {

    private final AuditRepository auditRepository;

    @GetMapping
    @Operation(summary = "List audit logs", description = "Lists audit log entries for all deals owned by the authenticated seller")
    public ResponseEntity<Page<AuditLogResponse>> getAuditLogs(
            @CurrentSeller UUID sellerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page must be >= 0 and size must be between 1 and 100");
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<AuditLog> auditLogsPage = auditRepository.findBySellerId(sellerId, pageable);
        List<AuditLogResponse> responses = auditLogsPage.getContent().stream()
                .map(AuditLogResponse::from)
                .toList();

        return ResponseEntity.ok(new PageImpl<>(responses, pageable, auditLogsPage.getTotalElements()));
    }
}
