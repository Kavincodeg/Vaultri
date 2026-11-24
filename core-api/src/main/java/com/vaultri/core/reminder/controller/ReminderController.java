package com.vaultri.core.reminder.controller;

import com.vaultri.core.reminder.service.ReminderService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reminders")
@RequiredArgsConstructor
@Tag(name = "Reminders", description = "Scheduled notifications and reminder dispatches APIs")
public class ReminderController {

    private final ReminderService reminderService;
}
