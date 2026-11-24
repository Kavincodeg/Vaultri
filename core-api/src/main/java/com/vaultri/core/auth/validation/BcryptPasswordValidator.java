package com.vaultri.core.auth.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.nio.charset.StandardCharsets;

public class BcryptPasswordValidator implements ConstraintValidator<BcryptPassword, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return false;
        }

        if (value.length() < 8) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate("Password must be at least 8 characters long")
                    .addConstraintViolation();
            return false;
        }

        byte[] utf8Bytes = value.getBytes(StandardCharsets.UTF_8);
        if (utf8Bytes.length > 72) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate("Password must not exceed 72 bytes in UTF-8 encoding")
                    .addConstraintViolation();
            return false;
        }

        return true;
    }
}
