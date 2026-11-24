package com.vaultri.core.auth.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a password satisfies BCrypt constraints:
 * - Minimum 8 characters.
 * - Maximum 72 BYTES in UTF-8 encoding (to prevent silent BCrypt truncation).
 */
@Documented
@Constraint(validatedBy = BcryptPasswordValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface BcryptPassword {
    String message() default "Password must be at least 8 characters and at most 72 bytes";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
