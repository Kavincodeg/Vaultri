package com.vaultri.core.payment.gateway;

import com.vaultri.core.payment.config.RazorpayProperties;
import com.vaultri.core.payment.entity.PaymentType;
import com.vaultri.core.payment.exception.GatewayUnavailableException;
import com.vaultri.core.payment.gateway.dto.CreateLinkRequest;
import com.vaultri.core.payment.gateway.dto.PaymentLinkResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class RazorpayGatewayTest {

    private RazorpayGateway razorpayGateway;
    private MockRestServiceServer mockServer;
    private RazorpayProperties properties;

    @BeforeEach
    void setUp() {
        MockEnvironment env = new MockEnvironment();
        properties = new RazorpayProperties(env);
        properties.setKeyId("rzp_test_key123");
        properties.setKeySecret("rzp_test_secret456");
        properties.setBaseUrl("https://api.razorpay.com");

        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        razorpayGateway = new RazorpayGateway(properties, builder);
    }

    @Test
    @DisplayName("createPaymentLink: sends correct URL, Basic Auth, amount in paise, notes, and parses response")
    void testCreatePaymentLinkSuccess() {
        UUID paymentId = UUID.randomUUID();
        UUID dealId = UUID.randomUUID();

        CreateLinkRequest request = new CreateLinkRequest(
                paymentId,
                50000L,
                "Deposit payment for deal",
                "Customer Name",
                "customer@test.com",
                "+919876543210",
                dealId,
                PaymentType.DEPOSIT
        );

        String responseJson = """
                {
                  "id": "plink_test_98765",
                  "short_url": "https://rzp.io/i/fake123",
                  "status": "created"
                }
                """;

        mockServer.expect(requestTo("https://api.razorpay.com/v1/payment_links"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", org.hamcrest.Matchers.startsWith("Basic ")))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.amount").value(50000))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.reference_id").value(paymentId.toString()))
                .andExpect(jsonPath("$.notes.dealId").value(dealId.toString()))
                .andExpect(jsonPath("$.notes.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.reminder_enable").value(false))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        PaymentLinkResponse response = razorpayGateway.createPaymentLink(request);
        mockServer.verify();

        assertThat(response.linkId()).isEqualTo("plink_test_98765");
        assertThat(response.shortUrl()).isEqualTo("https://rzp.io/i/fake123");
    }

    @Test
    @DisplayName("createPaymentLink: 4xx error maps to GatewayUnavailableException without leaking credentials")
    void testCreatePaymentLink4xxError() {
        CreateLinkRequest request = new CreateLinkRequest(
                UUID.randomUUID(), 50000L, "Desc", "Name", null, null, UUID.randomUUID(), PaymentType.DEPOSIT
        );

        mockServer.expect(requestTo("https://api.razorpay.com/v1/payment_links"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"error\":{\"description\":\"Invalid params\"}}"));

        assertThatThrownBy(() -> razorpayGateway.createPaymentLink(request))
                .isInstanceOf(GatewayUnavailableException.class)
                .hasMessageNotContaining("rzp_test_secret456")
                .hasMessageNotContaining("Authorization");
    }

    @Test
    @DisplayName("createPaymentLink: 5xx error maps to GatewayUnavailableException without leaking credentials")
    void testCreatePaymentLink5xxError() {
        CreateLinkRequest request = new CreateLinkRequest(
                UUID.randomUUID(), 50000L, "Desc", "Name", null, null, UUID.randomUUID(), PaymentType.DEPOSIT
        );

        mockServer.expect(requestTo("https://api.razorpay.com/v1/payment_links"))
                .andRespond(withServerError().body("Internal Server Error"));

        assertThatThrownBy(() -> razorpayGateway.createPaymentLink(request))
                .isInstanceOf(GatewayUnavailableException.class)
                .hasMessageNotContaining("rzp_test_secret456")
                .hasMessageNotContaining("Authorization");
    }

    @Test
    @DisplayName("createPaymentLink: timeout / IO error maps to GatewayUnavailableException without leaking credentials")
    void testCreatePaymentLinkTimeout() {
        CreateLinkRequest request = new CreateLinkRequest(
                UUID.randomUUID(), 50000L, "Desc", "Name", null, null, UUID.randomUUID(), PaymentType.DEPOSIT
        );

        mockServer.expect(requestTo("https://api.razorpay.com/v1/payment_links"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> razorpayGateway.createPaymentLink(request))
                .isInstanceOf(GatewayUnavailableException.class)
                .hasMessageNotContaining("rzp_test_secret456")
                .hasMessageNotContaining("Authorization");
    }

    @Test
    @DisplayName("cancelPaymentLink: sends correct URL, Basic Auth, empty JSON body, and succeeds")
    void testCancelPaymentLinkSuccess() {
        mockServer.expect(requestTo("https://api.razorpay.com/v1/payment_links/plink_12345/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", org.hamcrest.Matchers.startsWith("Basic ")))
                .andExpect(content().string("{}"))
                .andRespond(withSuccess("{\"id\":\"plink_12345\",\"status\":\"cancelled\"}", MediaType.APPLICATION_JSON));

        razorpayGateway.cancelPaymentLink("plink_12345");
        mockServer.verify();
    }

    @Test
    @DisplayName("cancelPaymentLink: 400 with terminal indicator (e.g. cancelled/expired/paid) is treated as benign ALREADY_TERMINAL")
    void testCancelPaymentLinkBenign400() {
        mockServer.expect(requestTo("https://api.razorpay.com/v1/payment_links/plink_already_cancelled/cancel"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"error\":{\"description\":\"Payment link is already cancelled\"}}"));

        razorpayGateway.cancelPaymentLink("plink_already_cancelled");
        mockServer.verify();
    }

    @Test
    @DisplayName("cancelPaymentLink: 400 without terminal indicator throws GatewayUnavailableException without credential leakage")
    void testCancelPaymentLinkNonTerminal400() {
        mockServer.expect(requestTo("https://api.razorpay.com/v1/payment_links/plink_bad/cancel"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"error\":{\"description\":\"Invalid request parameter\"}}"));

        assertThatThrownBy(() -> razorpayGateway.cancelPaymentLink("plink_bad"))
                .isInstanceOf(GatewayUnavailableException.class)
                .hasMessageNotContaining("rzp_test_secret456")
                .hasMessageNotContaining("Authorization");
    }

    @Test
    @DisplayName("cancelPaymentLink: 5xx server error throws GatewayUnavailableException without credential leakage")
    void testCancelPaymentLink5xxError() {
        mockServer.expect(requestTo("https://api.razorpay.com/v1/payment_links/plink_server_err/cancel"))
                .andRespond(withServerError().body("Gateway Error"));

        assertThatThrownBy(() -> razorpayGateway.cancelPaymentLink("plink_server_err"))
                .isInstanceOf(GatewayUnavailableException.class)
                .hasMessageNotContaining("rzp_test_secret456")
                .hasMessageNotContaining("Authorization");
    }

    @Test
    @DisplayName("cancelPaymentLink: timeout throws GatewayUnavailableException without credential leakage")
    void testCancelPaymentLinkTimeout() {
        mockServer.expect(requestTo("https://api.razorpay.com/v1/payment_links/plink_timeout/cancel"))
                .andRespond(withException(new SocketTimeoutException("Connection timed out")));

        assertThatThrownBy(() -> razorpayGateway.cancelPaymentLink("plink_timeout"))
                .isInstanceOf(GatewayUnavailableException.class)
                .hasMessageNotContaining("rzp_test_secret456")
                .hasMessageNotContaining("Authorization");
    }
}
