package com.vaultri.core.webhook.controller;

import com.vaultri.core.webhook.service.WebhookService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
@Tag(name = "Webhooks", description = "Razorpay and payment provider event receivers APIs")
public class WebhookController {

    private final WebhookService webhookService;
}
