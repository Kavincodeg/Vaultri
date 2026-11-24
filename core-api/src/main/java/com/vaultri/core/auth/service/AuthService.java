package com.vaultri.core.auth.service;

import com.vaultri.core.auth.repository.SellerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final SellerRepository sellerRepository;
}
