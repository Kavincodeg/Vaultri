package com.vaultri.core.auth;

import com.vaultri.core.AbstractIntegrationTest;
import com.vaultri.core.auth.dto.LoginRequest;
import com.vaultri.core.auth.dto.RefreshTokenRequest;
import com.vaultri.core.auth.dto.SignupRequest;
import com.vaultri.core.auth.entity.RefreshToken;
import com.vaultri.core.auth.repository.RefreshTokenRepository;
import com.vaultri.core.auth.service.AuthService;
import com.vaultri.core.auth.service.TokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
class RefreshTokenTransactionalRollbackTest extends AbstractIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private TokenService tokenService;

    @MockitoSpyBean
    private RefreshTokenRepository refreshTokenRepository;

    @Test
    @DisplayName("Refresh rollback: when saving new refresh token fails, old refresh token remains active")
    void testRefreshRollbackWhenNewTokenSaveFails() {
        String email = "rollback_test_" + UUID.randomUUID() + "@vaultri.com";
        String password = "Password123!";

        authService.signup(new SignupRequest(email, password, "Rollback Tester", null));
        var authResponse = authService.login(new LoginRequest(email, password));
        String rawRefreshToken = authResponse.refreshToken();
        String tokenHash = tokenService.hashToken(rawRefreshToken);

        RefreshToken initialToken = refreshTokenRepository.findByTokenHash(tokenHash).orElseThrow();
        assertThat(initialToken.getRevokedAt()).isNull();

        // Configure spy to throw an exception ONLY when saving a brand new refresh token (id is null)
        doThrow(new RuntimeException("Simulated database failure during new refresh token persistence"))
                .when(refreshTokenRepository)
                .save(argThat(token -> token != null && token.getId() == null));

        // Call refresh -> must fail due to the simulated exception
        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(rawRefreshToken)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated database failure");

        // Transaction must have rolled back: verify old token's revokedAt is still null
        RefreshToken tokenAfterRollback = refreshTokenRepository.findByTokenHash(tokenHash).orElseThrow();
        assertThat(tokenAfterRollback.getRevokedAt())
                .as("Old refresh token must remain active (revokedAt is null) after rollback")
                .isNull();
    }
}
