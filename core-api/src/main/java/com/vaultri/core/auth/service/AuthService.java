package com.vaultri.core.auth.service;

import com.vaultri.core.auth.config.JwtProperties;
import com.vaultri.core.auth.dto.*;
import com.vaultri.core.auth.entity.RefreshToken;
import com.vaultri.core.auth.entity.Seller;
import com.vaultri.core.auth.exception.DuplicateEmailException;
import com.vaultri.core.auth.repository.RefreshTokenRepository;
import com.vaultri.core.auth.repository.SellerRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class AuthService {

    private final SellerRepository sellerRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final JwtProperties jwtProperties;
    private final String dummyHash;

    public AuthService(
            SellerRepository sellerRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            TokenService tokenService,
            JwtProperties jwtProperties
    ) {
        this.sellerRepository = sellerRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.jwtProperties = jwtProperties;
        // Dynamically generate dummy BCrypt hash at startup using the injected PasswordEncoder bean
        this.dummyHash = passwordEncoder.encode("dummy_timing_salt_" + UUID.randomUUID());
    }

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

        try {
            Seller saved = sellerRepository.saveAndFlush(seller);
            return SellerResponse.from(saved);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateEmailException(normalizedEmail);
        }
    }

    /**
     * Authenticates a seller and issues an access token and refresh token.
     * Performs constant-time password check via dynamic dummy hash when email is not found
     * to prevent user enumeration attacks via response timing.
     */
    @Transactional
    public AuthResponse login(LoginRequest request) {
        String normalizedEmail = request.email().trim().toLowerCase();
        var sellerOpt = sellerRepository.findByEmailIgnoreCase(normalizedEmail);

        if (sellerOpt.isEmpty()) {
            // Constant-time execution against dynamic dummy hash
            passwordEncoder.matches(request.password(), dummyHash);
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
     * Uses atomic conditional update to ensure exactly one thread succeeds under concurrent requests.
     */
    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request) {
        String rawToken = request.refreshToken().trim();
        String tokenHash = tokenService.hashToken(rawToken);

        Instant now = Instant.now();
        // Atomic single conditional update: revoked_at is set if and only if it was null and unexpired
        int updated = refreshTokenRepository.revokeIfActive(tokenHash, now);
        if (updated != 1) {
            throw new BadCredentialsException("Invalid or expired refresh token");
        }

        RefreshToken token = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new BadCredentialsException("Invalid or expired refresh token"));

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
