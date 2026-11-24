package com.vaultri.core.auth;

import com.vaultri.core.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.isOneOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=prod",
        "POSTGRES_HOST=localhost",
        "POSTGRES_PORT=5432",
        "POSTGRES_DB=vaultri_prod",
        "POSTGRES_USER=vaultri",
        "POSTGRES_PASSWORD=vaultri",
        "REDIS_HOST=localhost",
        "REDIS_PORT=6379",
        "REDIS_PASSWORD=dummy",
        "MAIL_HOST=localhost",
        "MAIL_USERNAME=dummy",
        "MAIL_PASSWORD=dummy",
        "JWT_SECRET=test_secret_key_needs_to_be_at_least_256_bits_for_hmac_sha256_testing_only"
})
@ActiveProfiles(profiles = {"test", "prod"})
@AutoConfigureMockMvc
class ProdSecurityDocsTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Swagger & OpenAPI in prod: /v3/api-docs returns 401 or 404, never 200")
    void testOpenApiDocsDisabledOrProtectedInProd() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().is(isOneOf(401, 404)));
    }

    @Test
    @DisplayName("Swagger & OpenAPI in prod: /swagger-ui.html returns 401 or 404, never 200")
    void testSwaggerUiHtmlDisabledOrProtectedInProd() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is(isOneOf(401, 404)));
    }

    @Test
    @DisplayName("Swagger & OpenAPI in prod: /swagger-ui/index.html returns 401 or 404, never 200")
    void testSwaggerUiIndexDisabledOrProtectedInProd() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().is(isOneOf(401, 404)));
    }
}
