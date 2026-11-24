package com.vaultri.core.deal.exception;

import com.vaultri.core.deal.entity.DealStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DealExceptionHandlerTest {

    private MockMvc mockMvc;

    @RestController
    static class TestController {
        @GetMapping("/test/illegal-transition")
        public void throwIllegalTransition() {
            throw new IllegalDealTransitionException(DealStatus.DRAFT, DealStatus.COMPLETED);
        }

        @GetMapping("/test/deal-not-found")
        public void throwDealNotFound() {
            throw new DealNotFoundException(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        }

        @GetMapping("/test/invalid-fee")
        public void throwInvalidFee() {
            throw new InvalidCancellationFeeException("Fee exceeds maximum allowable");
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestController())
                .setControllerAdvice(new DealExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("IllegalDealTransitionException maps to HTTP 409 Conflict with RFC 7807 ProblemDetail")
    void testIllegalTransitionMapping() throws Exception {
        mockMvc.perform(get("/test/illegal-transition").accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)))
                .andExpect(jsonPath("$.title", is("Illegal Deal Transition")))
                .andExpect(jsonPath("$.detail", is("Illegal deal transition from DRAFT to COMPLETED")))
                .andExpect(jsonPath("$.from", is("DRAFT")))
                .andExpect(jsonPath("$.to", is("COMPLETED")));
    }

    @Test
    @DisplayName("DealNotFoundException maps to HTTP 404 Not Found with RFC 7807 ProblemDetail")
    void testDealNotFoundMapping() throws Exception {
        mockMvc.perform(get("/test/deal-not-found").accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.title", is("Deal Not Found")))
                .andExpect(jsonPath("$.detail", is("Deal not found: 11111111-1111-1111-1111-111111111111")))
                .andExpect(jsonPath("$.dealId", is("11111111-1111-1111-1111-111111111111")));
    }

    @Test
    @DisplayName("InvalidCancellationFeeException maps to HTTP 400 Bad Request with RFC 7807 ProblemDetail")
    void testInvalidFeeMapping() throws Exception {
        mockMvc.perform(get("/test/invalid-fee").accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.title", is("Invalid Cancellation Fee")))
                .andExpect(jsonPath("$.detail", is("Fee exceeds maximum allowable")));
    }
}
