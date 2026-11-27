package com.vaultri.core.reminder.controller;

import com.vaultri.core.auth.security.CurrentSeller;
import com.vaultri.core.common.dto.PageResponse;
import com.vaultri.core.common.exception.InvalidQueryParameterException;
import com.vaultri.core.reminder.dto.ReminderListItemResponse;
import com.vaultri.core.reminder.entity.Reminder;
import com.vaultri.core.reminder.repository.ReminderRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
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
@RequestMapping("/api/v1/reminders")
@RequiredArgsConstructor
@Tag(name = "Reminders", description = "Scheduled notifications and reminder dispatches APIs")
public class ReminderController {

    private final ReminderRepository reminderRepository;

    @GetMapping
    @Operation(summary = "List reminders", description = "Lists reminders for the authenticated seller's deals")
    public ResponseEntity<PageResponse<ReminderListItemResponse>> getReminders(
            @CurrentSeller UUID sellerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        if (page < 0 || size < 1 || size > 100) {
            throw new InvalidQueryParameterException("page must be >= 0 and size must be between 1 and 100");
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<Reminder> remindersPage = reminderRepository.findBySellerId(sellerId, pageable);
        List<ReminderListItemResponse> responses = remindersPage.getContent().stream()
                .map(ReminderListItemResponse::from)
                .toList();

        return ResponseEntity.ok(PageResponse.from(remindersPage, responses));
    }
}
