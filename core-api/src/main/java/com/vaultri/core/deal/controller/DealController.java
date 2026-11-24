package com.vaultri.core.deal.controller;

import com.vaultri.core.deal.service.DealService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/deals")
@RequiredArgsConstructor
@Tag(name = "Deals", description = "Deal lifecycle, terms, and protection management APIs")
public class DealController {

    private final DealService dealService;
}
