package com.vaultri.core.deal.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerSanitizationTest {

    @RestController
    static class TestController {
        @GetMapping("/test/bad-credentials")
        public void throwBadCredentials() {
            throw new BadCredentialsException("Internal auth details that must not leak");
        }

        @GetMapping("/test/sql-violation")
        public void throwSqlViolation() {
            throw new DataIntegrityViolationException("ERROR: duplicate key value violates unique constraint \"uq_seller_email\" Detail: Key (email)=(other_seller@test.com) already exists.");
        }
    }

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new TestController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    @DisplayName("BadCredentialsException returns fixed generic message 'Authentication required'")
    void testBadCredentialsGenericMessage() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/bad-credentials").accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.title", is("Authentication Failed")))
                .andExpect(jsonPath("$.detail", is("Authentication required")))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("Internal auth details");
        assertThat(body).doesNotContain("StackTrace");
    }

    @Test
    @DisplayName("DataIntegrityViolationException returns generic message and does not leak SQL or another seller's data")
    void testDataIntegrityViolationDoesNotLeakSqlOrSellerData() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/sql-violation").accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)))
                .andExpect(jsonPath("$.detail", is("Request conflicts with existing data")))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("other_seller@test.com");
        assertThat(body).doesNotContain("uq_seller_email");
        assertThat(body).doesNotContain("SQL");
        assertThat(body).doesNotContain("StackTrace");
    }
}
