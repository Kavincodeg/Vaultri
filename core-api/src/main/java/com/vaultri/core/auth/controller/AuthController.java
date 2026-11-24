package com.vaultri.core.auth.controller;

import com.vaultri.core.auth.dto.*;
import com.vaultri.core.auth.security.CurrentSeller;
import com.vaultri.core.auth.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Seller registration, login, token refresh, and profile APIs")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/api/v1/auth/signup")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register seller", description = "Creates a new seller account with BCrypt password hashing")
    public ResponseEntity<SellerResponse> signup(@Valid @RequestBody SignupRequest request) {
        SellerResponse response = authService.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/api/v1/auth/login")
    @Operation(summary = "Seller login", description = "Authenticates seller credentials and returns access and refresh tokens")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/v1/auth/refresh")
    @Operation(summary = "Refresh access token", description = "Rotates refresh token and issues a new access token")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        AuthResponse response = authService.refresh(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/v1/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Logout seller", description = "Revokes the active refresh token")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/me")
    @Operation(summary = "Get current seller profile", description = "Returns authenticated seller profile extracted from JWT")
    public ResponseEntity<MeResponse> getMe(@CurrentSeller UUID sellerId) {
        MeResponse response = authService.getMe(sellerId);
        return ResponseEntity.ok(response);
    }
}
