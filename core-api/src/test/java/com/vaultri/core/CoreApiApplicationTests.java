package com.vaultri.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class CoreApiApplicationTests extends AbstractIntegrationTest {

    @Test
    @DisplayName("Application context loads successfully with real PostgreSQL container")
    void contextLoads() {
        // Verifies Spring Boot 3 context configuration, bean wiring, and Flyway migration against PostgreSQL container
    }
}
