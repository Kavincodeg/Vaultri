package com.vaultri.core.auth.service;

import com.vaultri.core.auth.config.JwtProperties;
import com.vaultri.core.auth.dto.*;
import com.vaultri.core.auth.entity.RefreshToken;
import com.vaultri.core.auth.entity.Seller;
import com.vaultri.core.auth.exception.DuplicateEmailException;
import com.vaultri.core.auth.repository.RefreshTokenRepository;
import com.vaultri.core.auth.repository.SellerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    // Pre-computed BCrypt hash of a dummy password to perform constant-time comparison on nonexistent emails
    private static final String DUMMY_HASH = "$2a$10$wK1c5xH2qfH1k7eU8W.Ptu0R5n/Fv1O5D6k8h1t3k7y2k5h8k9k2";

    private final SellerRepository sellerRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final JwtProperties jwtProperties;

    /**
     * Registers a new seller account.
     * Normalized email to lowercase; enforces case-insensitive uniqueness.
     * Never returns the password hash.
     */
    @Transactional
    public SellerResponse signup(SignupRequest request) {
        String normalizedEmail = request.email().trim().toLowerCase();

        if (sellerRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new DuplicateEmailException(normalizedEmail);
        }

        Seller seller = Seller.builder()
                .email(normalizedEmail)
                .passwordHash(passwordEncoder.encode(request.password()))
                .name(request.name().trim())
                .phone(request.phone() != null ? request.phone().trim() : null)
                .plan("free")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        Seller saved = sellerRepository.save(seller);
        return SellerResponse.from(saved);
    }

    /**
     * Authenticates a seller and issues an access token and refresh token.
     * Performs constant-time password check via dummy hash when email is not found
     * to prevent user enumeration attacks via response timing.
     */
    @Transactional
    public AuthResponse login(LoginRequest request) {
        String normalizedEmail = request.email().trim().toLowerCase();
        var sellerOpt = sellerRepository.findByEmailIgnoreCase(normalizedEmail);

        if (sellerOpt.isEmpty()) {
            // Constant-time execution against dummy hash
            passwordEncoder.matches(request.password(), DUMMY_HASH);
            throw new BadCredentialsException("Invalid email or password");
        }

        Seller seller = sellerOpt.get();
        if (!passwordEncoder.matches(request.password(), seller.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        return createAuthSession(seller);
    }

    /**
     * Exchanges a valid refresh token for a newly rotated access token and refresh token.
     * Reusing a revoked or expired token throws BadCredentialsException (HTTP 401).
     */
    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request) {
        String rawToken = request.refreshToken().trim();
        String tokenHash = tokenService.hashToken(rawToken);

        RefreshToken token = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new BadCredentialsException("Invalid or expired refresh token"));

        Instant now = Instant.now();
        if (token.getRevokedAt() != null || token.getExpiresAt().isBefore(now)) {
            throw new BadCredentialsException("Invalid or expired refresh token");
        }

        // Rotate: revoke the current token immediately upon exchange
        token.setRevokedAt(now);
        refreshTokenRepository.save(token);

        return createAuthSession(token.getSeller());
    }

    /**
     * Revokes the supplied refresh token.
     */
    @Transactional
    public void logout(LogoutRequest request) {
        String rawToken = request.refreshToken().trim();
        String tokenHash = tokenService.hashToken(rawToken);

        refreshTokenRepository.findByTokenHash(tokenHash).ifPresent(token -> {
            if (token.getRevokedAt() == null) {
                token.setRevokedAt(Instant.now());
                refreshTokenRepository.save(token);
            }
        });
    }

    /**
     * Retrieves the profile of the currently authenticated seller.
     */
    @Transactional(readOnly = true)
    public MeResponse getMe(UUID sellerId) {
        Seller seller = sellerRepository.findById(sellerId)
                .orElseThrow(() -> new BadCredentialsException("Seller account not found"));
        return MeResponse.from(seller);
    }

    private AuthResponse createAuthSession(Seller seller) {
        String accessToken = tokenService.generateAccessToken(seller);
        String rawRefreshToken = tokenService.generateRefreshToken();

        Instant now = Instant.now();
        Instant expiresAt = now.plus(jwtProperties.getRefreshTokenTtl());

        RefreshToken refreshTokenEntity = RefreshToken.builder()
                .seller(seller)
                .tokenHash(tokenService.hashToken(rawRefreshToken))
                .expiresAt(expiresAt)
                .createdAt(now)
                .build();

        refreshTokenRepository.save(refreshTokenEntity);

        return AuthResponse.of(accessToken, rawRefreshToken, tokenService.getAccessTokenExpiresInSeconds());
    }
}
