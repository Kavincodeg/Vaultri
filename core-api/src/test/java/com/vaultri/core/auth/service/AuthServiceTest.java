package com.vaultri.core.auth.service;

import com.vaultri.core.auth.config.JwtProperties;
import com.vaultri.core.auth.dto.LoginRequest;
import com.vaultri.core.auth.entity.Seller;
import com.vaultri.core.auth.repository.RefreshTokenRepository;
import com.vaultri.core.auth.repository.SellerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private SellerRepository sellerRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private TokenService tokenService;

    @Mock
    private JwtProperties jwtProperties;

    @Spy
    private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                sellerRepository,
                refreshTokenRepository,
                passwordEncoder,
                tokenService,
                jwtProperties
        );
    }

    @Test
    @DisplayName("Dummy hash: dynamically generated at startup via PasswordEncoder")
    void testDynamicDummyHashGeneratedAtStartup() {
        // The spy should have recorded at least one encode() call during AuthService construction
        verify(passwordEncoder, atLeastOnce()).encode(anyString());
    }

    @Test
    @DisplayName("Login failure: encoder.matches called exactly once on unknown email")
    void testMatchesCalledExactlyOnceOnUnknownEmail() {
        String email = "unknown@vaultri.com";
        String password = "SomePassword123!";
        when(sellerRepository.findByEmailIgnoreCase(email)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest(email, password)))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid email or password");

        // Verify matches was called exactly once with the provided password
        verify(passwordEncoder, times(1)).matches(eq(password), anyString());
    }

    @Test
    @DisplayName("Login failure: encoder.matches called exactly once on wrong password")
    void testMatchesCalledExactlyOnceOnWrongPassword() {
        String email = "known@vaultri.com";
        String correctPassword = "CorrectPassword123!";
        String wrongPassword = "WrongPassword123!";
        String encodedHash = passwordEncoder.encode(correctPassword);

        // Reset spy counts after setup encoding
        clearInvocations(passwordEncoder);

        Seller seller = Seller.builder()
                .id(UUID.randomUUID())
                .email(email)
                .passwordHash(encodedHash)
                .name("Known Seller")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(sellerRepository.findByEmailIgnoreCase(email)).thenReturn(Optional.of(seller));

        assertThatThrownBy(() -> authService.login(new LoginRequest(email, wrongPassword)))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid email or password");

        // Verify matches was called exactly once with the wrong password against seller's hash
        verify(passwordEncoder, times(1)).matches(eq(wrongPassword), eq(encodedHash));
    }
}
