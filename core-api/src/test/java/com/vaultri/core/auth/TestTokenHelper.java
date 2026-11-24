package com.vaultri.core.auth;

import com.vaultri.core.auth.config.JwtProperties;
import com.vaultri.core.auth.entity.Seller;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Test helper for generating custom/expired tokens using the application's JwtEncoder bean.
 * Kept exclusively in test sources so production code contains no arbitrary token-minting APIs.
 */
@Component
@RequiredArgsConstructor
public class TestTokenHelper {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;

    /**
     * Generates an expired token using the application's JwtEncoder bean for test verification.
     */
    public String generateExpiredToken(Seller seller) {
        Instant issuedAt = Instant.now().minusSeconds(3600);
        Instant expiredAt = Instant.now().minusSeconds(1800);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.getIssuer())
                .issuedAt(issuedAt)
                .expiresAt(expiredAt)
                .subject(seller.getId().toString())
                .claim("email", seller.getEmail())
                .build();

        JwsHeader jwsHeader = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();
    }
}
