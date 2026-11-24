package com.vaultri.core.auth.dto;

import com.vaultri.core.auth.validation.BcryptPassword;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record SignupRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Must be a valid email format")
        String email,

        @NotBlank(message = "Password is required")
        @BcryptPassword
        String password,

        @NotBlank(message = "Name is required")
        String name,

        String phone
) {}
