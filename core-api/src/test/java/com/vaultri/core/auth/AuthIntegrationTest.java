package com.vaultri.core.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.vaultri.core.AbstractIntegrationTest;
import com.vaultri.core.auth.config.JwtProperties;
import com.vaultri.core.auth.dto.*;
import com.vaultri.core.auth.entity.Seller;
import com.vaultri.core.auth.repository.RefreshTokenRepository;
import com.vaultri.core.auth.repository.SellerRepository;
import com.vaultri.core.auth.service.TokenService;
import com.vaultri.core.deal.dto.CreateDealCommand;
import com.vaultri.core.deal.entity.Deal;
import com.vaultri.core.deal.service.DealService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SellerRepository sellerRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private TestTokenHelper testTokenHelper;

    @Autowired
    private DealService dealService;

    @Autowired
    private UserDetailsService userDetailsService;

    @Autowired
    private JwtProperties jwtProperties;

    @Test
    @DisplayName("Signup: valid payload succeeds (201), normalizes lowercase email, rejects duplicate case-insensitively (409)")
    void testSignupSuccessAndDuplicateEmail() throws Exception {
        String baseEmail = "Seller_" + UUID.randomUUID() + "@Vaultri.com";
        String normalizedEmail = baseEmail.trim().toLowerCase();

        SignupRequest validRequest = new SignupRequest(
                baseEmail,
                "StrongPassword123!",
                "Alpha Seller",
                "+919876543210"
        );

        MvcResult result = mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.email").value(normalizedEmail))
                .andExpect(jsonPath("$.name").value("Alpha Seller"))
                .andExpect(jsonPath("$.phone").value("+919876543210"))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertThat(responseBody).doesNotContain("password");
        assertThat(responseBody).doesNotContain("$2a$");

        // Duplicate email in lowercase must fail with 409
        SignupRequest duplicateLower = new SignupRequest(
                normalizedEmail,
                "AnotherPassword123!",
                "Duplicate Lower",
                null
        );

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicateLower)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Duplicate Email"));

        // Duplicate email in uppercase must also fail with 409
        SignupRequest duplicateUpper = new SignupRequest(
                normalizedEmail.toUpperCase(),
                "AnotherPassword123!",
                "Duplicate Upper",
                null
        );

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicateUpper)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Duplicate Email"));
    }

    @Test
    @DisplayName("Signup: validation errors for short password, over 72-byte password, and invalid email return 400")
    void testSignupValidationConstraints() throws Exception {
        // 1. Password too short (< 8 chars)
        SignupRequest shortPassword = new SignupRequest(
                "valid@test.com",
                "short7",
                "Name",
                null
        );
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(shortPassword)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"));

        // 2. Password too long (> 72 bytes in UTF-8)
        // 73 ASCII characters = 73 bytes
        String seventyThreeBytePassword = "A".repeat(73);
        SignupRequest longPassword = new SignupRequest(
                "valid@test.com",
                seventyThreeBytePassword,
                "Name",
                null
        );
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(longPassword)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"));

        // 3. Invalid email format
        SignupRequest invalidEmail = new SignupRequest(
                "not-an-email-address",
                "ValidPassword123!",
                "Name",
                null
        );
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidEmail)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"));
    }

    @Test
    @DisplayName("Login: successful login returns tokens; wrong password and unknown email return identical 401 bodies")
    void testLoginSuccessAndIdentical401Responses() throws Exception {
        String email = "login_test_" + UUID.randomUUID() + "@vaultri.com";
        String password = "CorrectPassword123!";

        SignupRequest signup = new SignupRequest(email, password, "Login Tester", null);
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signup)))
                .andExpect(status().isCreated());

        // 1. Successful login
        LoginRequest validLogin = new LoginRequest(email, password);
        MvcResult successResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validLogin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn();

        String successBody = successResult.getResponse().getContentAsString();
        assertThat(successBody).doesNotContain("$2a$");

        // 2. Wrong password for existing user
        LoginRequest wrongPassword = new LoginRequest(email, "WrongPassword123!");
        MvcResult wrongPassResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wrongPassword)))
                .andExpect(status().isUnauthorized())
                .andReturn();

        // 3. Unknown email
        LoginRequest unknownEmail = new LoginRequest("unknown_" + UUID.randomUUID() + "@vaultri.com", password);
        MvcResult unknownEmailResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(unknownEmail)))
                .andExpect(status().isUnauthorized())
                .andReturn();

        // Compare bodies: must be identical ProblemDetail structures and details
        JsonNode wrongPassJson = objectMapper.readTree(wrongPassResult.getResponse().getContentAsString());
        JsonNode unknownEmailJson = objectMapper.readTree(unknownEmailResult.getResponse().getContentAsString());

        assertThat(wrongPassJson.get("status").asInt()).isEqualTo(401);
        assertThat(unknownEmailJson.get("status").asInt()).isEqualTo(401);
        assertThat(wrongPassJson.get("detail").asText()).isEqualTo("Authentication required");
        assertThat(unknownEmailJson.get("detail").asText()).isEqualTo("Authentication required");
        assertThat(wrongPassJson.get("title").asText()).isEqualTo("Authentication Failed");
        assertThat(unknownEmailJson.get("title").asText()).isEqualTo("Authentication Failed");
    }

    @Test
    @DisplayName("Tokens: request with no token, tampered token, expired token, and foreign-key token all return 401")
    void testTokenSecurityVerification() throws Exception {
        String email = "token_sec_" + UUID.randomUUID() + "@vaultri.com";
        SignupRequest signup = new SignupRequest(email, "ValidPassword123!", "Token Tester", null);
        mockMvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(signup)));

        Seller seller = sellerRepository.findByEmailIgnoreCase(email).orElseThrow();
        String validToken = tokenService.generateAccessToken(seller);

        // 1. No token -> 401
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        // 2. Tampered token -> 401
        String tamperedToken = validToken.substring(0, validToken.length() - 5) + "abcde";
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + tamperedToken))
                .andExpect(status().isUnauthorized());

        // 3. Expired token -> 401
        String expiredToken = testTokenHelper.generateExpiredToken(seller);
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized());

        // 4. Token signed with a completely different secret key -> 401
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .issuer("vaultri-core-api")
                .subject(seller.getId().toString())
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + 60000))
                .build();
        SignedJWT foreignSigned = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimsSet);
        foreignSigned.sign(new MACSigner("different-foreign-secret-key-that-is-at-least-32-bytes!".getBytes(StandardCharsets.UTF_8)));
        String foreignToken = foreignSigned.serialize();

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + foreignToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Refresh: rotation issues new pair, revokes old refresh token (401 on reuse), and logout revokes token")
    void testRefreshRotationAndLogout() throws Exception {
        String email = "refresh_test_" + UUID.randomUUID() + "@vaultri.com";
        String password = "StrongPassword123!";

        mockMvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new SignupRequest(email, password, "Refresh Tester", null))));

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn();

        AuthResponse initialAuth = objectMapper.readValue(loginResult.getResponse().getContentAsString(), AuthResponse.class);
        String initialRefresh = initialAuth.refreshToken();

        // 1. Refresh exchange: succeeds and returns new tokens
        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest(initialRefresh))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();

        AuthResponse secondAuth = objectMapper.readValue(refreshResult.getResponse().getContentAsString(), AuthResponse.class);
        String secondAccess = secondAuth.accessToken();
        String secondRefresh = secondAuth.refreshToken();

        // New access token works
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + secondAccess))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));

        // 2. Reusing old refresh token -> must fail with 401
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest(initialRefresh))))
                .andExpect(status().isUnauthorized());

        // 3. Logout with current refresh token -> 204
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + secondAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LogoutRequest(secondRefresh))))
                .andExpect(status().isNoContent());

        // 4. Attempt to refresh with logged-out refresh token -> 401
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest(secondRefresh))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/me: returns authenticated seller profile and contains no password hash")
    void testGetMeAuthenticated() throws Exception {
        String email = "me_test_" + UUID.randomUUID() + "@vaultri.com";
        String password = "Password123!";

        mockMvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new SignupRequest(email, password, "Me Tester", "+919876543210"))));

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn();

        AuthResponse auth = objectMapper.readValue(loginResult.getResponse().getContentAsString(), AuthResponse.class);

        MvcResult meResult = mockMvc.perform(get("/api/v1/me")
                        .header("Authorization", "Bearer " + auth.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.name").value("Me Tester"))
                .andExpect(jsonPath("$.phone").value("+919876543210"))
                .andExpect(jsonPath("$.plan").value("free"))
                .andReturn();

        String body = meResult.getResponse().getContentAsString();
        assertThat(body).doesNotContain("password");
        assertThat(body).doesNotContain("$2a$");
    }

    @Test
    @DisplayName("End-to-end deal ownership: Seller A token accessing Seller B deal returns 404 identical to nonexistent deal")
    void testDealOwnershipEnforcedByJwt() throws Exception {
        // Create Seller A
        String emailA = "seller_a_" + UUID.randomUUID() + "@vaultri.com";
        mockMvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new SignupRequest(emailA, "PasswordA123!", "Seller A", null))));

        Seller sellerA = sellerRepository.findByEmailIgnoreCase(emailA).orElseThrow();
        String tokenA = tokenService.generateAccessToken(sellerA);

        // Create Seller B
        String emailB = "seller_b_" + UUID.randomUUID() + "@vaultri.com";
        mockMvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new SignupRequest(emailB, "PasswordB123!", "Seller B", null))));

        Seller sellerB = sellerRepository.findByEmailIgnoreCase(emailB).orElseThrow();
        String tokenB = tokenService.generateAccessToken(sellerB);

        // Seller B creates a deal
        Deal dealB = dealService.createDraft(sellerB.getId(), CreateDealCommand.builder()
                .customerName("Client B")
                .price(150000L)
                .depositPercent(20)
                .title("Deal of B")
                .build());

        // 1. Seller A requests Seller B's deal -> returns 404
        MvcResult sellerAAccessBResult = mockMvc.perform(get("/api/v1/deals/" + dealB.getId())
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Deal Not Found"))
                .andReturn();

        // 2. Seller A requests a completely nonexistent deal -> returns 404 with identical ProblemDetail structure
        UUID randomId = UUID.randomUUID();
        MvcResult sellerANonexistentResult = mockMvc.perform(get("/api/v1/deals/" + randomId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Deal Not Found"))
                .andReturn();

        JsonNode crossSellerJson = objectMapper.readTree(sellerAAccessBResult.getResponse().getContentAsString());
        JsonNode nonexistentJson = objectMapper.readTree(sellerANonexistentResult.getResponse().getContentAsString());

        assertThat(crossSellerJson.get("status").asInt()).isEqualTo(404);
        assertThat(nonexistentJson.get("status").asInt()).isEqualTo(404);
        assertThat(crossSellerJson.get("title").asText()).isEqualTo(nonexistentJson.get("title").asText());
        assertThat(crossSellerJson.get("detail").asText()).isEqualTo("Deal not found: " + dealB.getId());
        assertThat(nonexistentJson.get("detail").asText()).isEqualTo("Deal not found: " + randomId);

        // 3. Seller B requests their own deal -> returns 200 with DealResponse
        mockMvc.perform(get("/api/v1/deals/" + dealB.getId())
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(dealB.getId().toString()))
                .andExpect(jsonPath("$.sellerId").value(sellerB.getId().toString()))
                .andExpect(jsonPath("$.customerName").value("Client B"))
                .andExpect(jsonPath("$.price").value(150000))
                .andExpect(jsonPath("$.depositAmount").value(30000));
    }

    @Test
    @DisplayName("Hardening 1: Atomic refresh rotation under 10 concurrent threads (repeated 10 times)")
    void testConcurrentRefreshRotation() throws Exception {
        int repetitions = 10;
        int threadCount = 10;

        for (int rep = 0; rep < repetitions; rep++) {
            String email = "refresh_race_" + rep + "_" + UUID.randomUUID() + "@vaultri.com";
            String password = "Password123!";

            mockMvc.perform(post("/api/v1/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new SignupRequest(email, password, "Race Tester " + rep, null))))
                    .andExpect(status().isCreated());

            MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
                    .andExpect(status().isOk())
                    .andReturn();

            AuthResponse loginResponse = objectMapper.readValue(loginResult.getResponse().getContentAsString(), AuthResponse.class);
            String sharedRefreshToken = loginResponse.refreshToken();

            Seller seller = sellerRepository.findByEmailIgnoreCase(email).orElseThrow();
            int initialCount = refreshTokenRepository.findBySellerId(seller.getId()).size();
            assertThat(initialCount).isEqualTo(1);

            CountDownLatch readyLatch = new CountDownLatch(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    readyLatch.countDown();
                    startLatch.await();

                    MvcResult res = mockMvc.perform(post("/api/v1/auth/refresh")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(new RefreshTokenRequest(sharedRefreshToken))))
                            .andReturn();

                    return res.getResponse().getStatus();
                }));
            }

            readyLatch.await(5, TimeUnit.SECONDS);
            startLatch.countDown();

            int count200 = 0;
            int count401 = 0;
            for (Future<Integer> f : futures) {
                int statusCode = f.get(10, TimeUnit.SECONDS);
                if (statusCode == 200) count200++;
                else if (statusCode == 401) count401++;
            }
            executor.shutdown();

            assertThat(count200)
                    .as("Exactly one refresh call must succeed with 200 on rep %d", rep)
                    .isEqualTo(1);
            assertThat(count401)
                    .as("The other nine refresh calls must fail with 401 on rep %d", rep)
                    .isEqualTo(9);

            List<?> tokensAfter = refreshTokenRepository.findBySellerId(seller.getId());
            assertThat(tokensAfter)
                    .as("Exactly one new refresh token row must exist (total 2) on rep %d", rep)
                    .hasSize(2);
        }
    }

    @Test
    @DisplayName("Hardening 2: Concurrent signup with same email (mixed case) across 10 threads")
    void testConcurrentSignupSameEmail() throws Exception {
        int threadCount = 10;
        String baseEmail = "concurrent_user_" + UUID.randomUUID() + "@vaultri.com";

        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();

                // Vary upper and lower case
                String emailVariant = (index % 2 == 0)
                        ? baseEmail.toUpperCase()
                        : (index % 3 == 0)
                        ? baseEmail.substring(0, 5).toUpperCase() + baseEmail.substring(5).toLowerCase()
                        : baseEmail.toLowerCase();

                SignupRequest signup = new SignupRequest(
                        emailVariant,
                        "Password123!",
                        "User " + index,
                        null
                );

                MvcResult res = mockMvc.perform(post("/api/v1/auth/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(signup)))
                        .andReturn();

                return res.getResponse().getStatus();
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        int count201 = 0;
        int count409 = 0;
        int count500 = 0;
        for (Future<Integer> f : futures) {
            int status = f.get(10, TimeUnit.SECONDS);
            if (status == 201) count201++;
            else if (status == 409) count409++;
            else if (status == 500) count500++;
        }
        executor.shutdown();

        assertThat(count201).as("Exactly one signup must succeed with 201").isEqualTo(1);
        assertThat(count409).as("The rest must fail with 409 Conflict").isEqualTo(9);
        assertThat(count500).as("None must fail with 500 Internal Server Error").isEqualTo(0);
    }

    @Test
    @DisplayName("Hardening 3: JWT decoder strictness - rejects wrong issuer, alg 'none', and HS384")
    void testJwtDecoderStrictness() throws Exception {
        String email = "jwt_strict_" + UUID.randomUUID() + "@vaultri.com";
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SignupRequest(email, "Password123!", "Strict Tester", null))))
                .andExpect(status().isCreated());

        Seller seller = sellerRepository.findByEmailIgnoreCase(email).orElseThrow();
        byte[] secretBytes = jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8);

        // 1. Token with wrong issuer returns 401
        SignedJWT wrongIssuerJwt = new SignedJWT(
                new JWSHeader(JWSAlgorithm.HS256),
                new JWTClaimsSet.Builder()
                        .issuer("evil-issuer")
                        .subject(seller.getId().toString())
                        .claim("email", seller.getEmail())
                        .issueTime(new Date())
                        .expirationTime(new Date(System.currentTimeMillis() + 60000))
                        .build()
        );
        wrongIssuerJwt.sign(new MACSigner(secretBytes));
        String wrongIssuerToken = wrongIssuerJwt.serialize();

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + wrongIssuerToken))
                .andExpect(status().isUnauthorized());

        // 2. Unsigned token with alg "none" returns 401
        PlainJWT plainJwt = new PlainJWT(
                new JWTClaimsSet.Builder()
                        .issuer(jwtProperties.getIssuer())
                        .subject(seller.getId().toString())
                        .claim("email", seller.getEmail())
                        .issueTime(new Date())
                        .expirationTime(new Date(System.currentTimeMillis() + 60000))
                        .build()
        );
        String unsignedToken = plainJwt.serialize();

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + unsignedToken))
                .andExpect(status().isUnauthorized());

        // 3. Token signed with HS384 using the same secret returns 401
        SignedJWT hs384Jwt = new SignedJWT(
                new JWSHeader(JWSAlgorithm.HS384),
                new JWTClaimsSet.Builder()
                        .issuer(jwtProperties.getIssuer())
                        .subject(seller.getId().toString())
                        .claim("email", seller.getEmail())
                        .issueTime(new Date())
                        .expirationTime(new Date(System.currentTimeMillis() + 60000))
                        .build()
        );
        hs384Jwt.sign(new MACSigner(secretBytes));
        String hs384Token = hs384Jwt.serialize();

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + hs384Token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Hardening 4: No default login - HTTP Basic returns 401 and UserDetailsService stub throws")
    void testNoDefaultLoginHttpBasicReturns401() throws Exception {
        // HTTP Basic credentials "user:user" on protected endpoint returns 401
        String basicUser = Base64.getEncoder().encodeToString("user:user".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Basic " + basicUser))
                .andExpect(status().isUnauthorized());

        // HTTP Basic credentials "admin:admin" on protected endpoint returns 401
        String basicAdmin = Base64.getEncoder().encodeToString("admin:admin".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Basic " + basicAdmin))
                .andExpect(status().isUnauthorized());

        // Verify UserDetailsService bean throws UsernameNotFoundException
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> userDetailsService.loadUserByUsername("user"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
