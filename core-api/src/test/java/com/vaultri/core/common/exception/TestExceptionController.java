package com.vaultri.core.common.exception;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test/exceptions")
@Profile("test")
public class TestExceptionController {

    @GetMapping("/illegal-argument")
    public void throwIllegalArgumentException() {
        throw new IllegalArgumentException("Unrelated internal argument error");
    }
}
