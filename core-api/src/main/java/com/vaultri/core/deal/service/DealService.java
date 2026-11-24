package com.vaultri.core.deal.service;

import com.vaultri.core.deal.repository.DealRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DealService {

    private final DealRepository dealRepository;
}
