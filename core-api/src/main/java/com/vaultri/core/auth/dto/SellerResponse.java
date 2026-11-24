package com.vaultri.core.auth.dto;

import com.vaultri.core.auth.entity.Seller;

import java.time.Instant;
import java.util.UUID;

public record SellerResponse(
        UUID id,
        String email,
        String name,
        String phone,
        String plan,
        Instant createdAt
) {
    public static SellerResponse from(Seller seller) {
        return new SellerResponse(
                seller.getId(),
                seller.getEmail(),
                seller.getName(),
                seller.getPhone(),
                seller.getPlan(),
                seller.getCreatedAt()
        );
    }
}
