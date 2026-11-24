package com.vaultri.core.report.service;

import com.vaultri.core.deal.repository.DealRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReportService {

    private final DealRepository dealRepository;
}
