package com.vaultri.core;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared abstract base test class for all tests requiring a database.
 * Uses a singleton PostgreSQL Testcontainer (postgres:16-alpine) managed via Spring Boot 3.1+ @ServiceConnection.
 * Flyway is enabled so that migrations run against real PostgreSQL.
 * The container is reused across all test classes for maximum execution speed.
 */
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("vaultri_test")
            .withUsername("test_user")
            .withPassword("test_password");

    static {
        postgres.start();
    }
}
