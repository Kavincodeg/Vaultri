package com.vaultri.core.auth.dto;

import com.vaultri.core.auth.entity.Seller;

import java.util.UUID;

public record MeResponse(
        UUID id,
        String email,
        String name,
        String phone,
        String plan
) {
    public static MeResponse from(Seller seller) {
        return new MeResponse(
                seller.getId(),
                seller.getEmail(),
                seller.getName(),
                seller.getPhone(),
                seller.getPlan()
        );
    }
}
