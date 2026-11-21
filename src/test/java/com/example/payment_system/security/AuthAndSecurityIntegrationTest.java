package com.example.payment_system.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "transfer.stream.enabled=false",
        "report.stream.enabled=false",
        "rate-limit.enabled=false"
})
@AutoConfigureMockMvc
class AuthAndSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private record UserSession(long userId, long accountId, String email, String token) {}

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
    }

    private UserSession registerUser(String prefix) throws Exception {
        String email = uniqueEmail(prefix);
        MvcResult res = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", prefix,
                                "email", email,
                                "password", "password123"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.role").value("USER"))
                .andReturn();

        JsonNode body = objectMapper.readTree(res.getResponse().getContentAsString());
        long userId = body.get("user").get("id").asLong();
        String token = body.get("accessToken").asText();

        MvcResult acctRes = mockMvc.perform(get("/api/accounts/user/{userId}", userId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(1000))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();

        long accountId = objectMapper.readTree(acctRes.getResponse().getContentAsString()).get("id").asLong();
        return new UserSession(userId, accountId, email, token);
    }

    private String login(String email, String password) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", password
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();

        return objectMapper.readTree(res.getResponse().getContentAsString())
                .get("accessToken")
                .asText();
    }

    @Test
    void unauthenticatedRequests_return401JsonErrorResponse() throws Exception {
        mockMvc.perform(get("/api/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        mockMvc.perform(get("/api/accounts/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fromAccountId", 1,
                                "toAccountId", 2,
                                "amount", 10,
                                "idempotencyKey", "unauth-1"
                        ))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void invalidBearerToken_returns401() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid.jwt.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void registerLoginAndMe_fullLifecycleWorks() throws Exception {
        UserSession alice = registerUser("alice");

        // Duplicate registration -> 409
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Alice Dup",
                                "email", alice.email(),
                                "password", "password123"
                        ))))
                .andExpect(status().isConflict());

        // Wrong password -> 401
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", alice.email(),
                                "password", "wrongpass"
                        ))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));

        // Case-insensitive email login -> 200
        String freshToken = login(alice.email().toUpperCase(), "password123");

        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + freshToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alice.userId()))
                .andExpect(jsonPath("$.email").value(alice.email()))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void rbac_regularUserBlockedFromAdminEndpoints_adminAllowedAndCanProvision() throws Exception {
        UserSession alice = registerUser("alice-rbac");
        String adminToken = login("admin@paymentsys.local", "admin12345");

        // USER blocked from ADMIN endpoints -> 403
        mockMvc.perform(get("/api/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token()))
                .andExpect(status().isForbidden());

        String provisionedEmail = uniqueEmail("provisioned");
        mockMvc.perform(post("/api/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Blocked",
                                "email", provisionedEmail,
                                "password", "password123"
                        ))))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/admin/accounts/{id}/status", alice.accountId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"FROZEN\"}"))
                .andExpect(status().isForbidden());

        // ADMIN can list users and provision a new user who can log in
        mockMvc.perform(get("/api/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mockMvc.perform(post("/api/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Provisioned User",
                                "email", provisionedEmail,
                                "password", "password123",
                                "role", "USER"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(provisionedEmail))
                .andExpect(jsonPath("$.role").value("USER"));

        String provisionedToken = login(provisionedEmail, "password123");
        assertThat(provisionedToken).isNotBlank();
    }

    @Test
    void ownershipAndTransferAuthorization_enforcedAcrossUsersAndAdmin() throws Exception {
        UserSession alice = registerUser("alice-own");
        UserSession bob = registerUser("bob-own");
        UserSession charlie = registerUser("charlie-own");
        String adminToken = login("admin@paymentsys.local", "admin12345");

        // Alice cannot read Bob's user or account -> 403
        mockMvc.perform(get("/api/users/{id}", bob.userId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/accounts/user/{userId}", bob.userId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/accounts/{id}", bob.accountId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token()))
                .andExpect(status().isForbidden());

        // Admin can read Bob's user and account -> 200
        mockMvc.perform(get("/api/users/{id}", bob.userId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/accounts/{id}", bob.accountId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        // Alice cannot debit Bob's account -> 403
        mockMvc.perform(post("/api/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fromAccountId", bob.accountId(),
                                "toAccountId", alice.accountId(),
                                "amount", 100,
                                "idempotencyKey", "steal-" + UUID.randomUUID()
                        ))))
                .andExpect(status().isForbidden());

        // Alice initiates transfer to Bob -> 202, replay -> 200
        String idemKey = "pay-" + UUID.randomUUID();
        String transferPayload = objectMapper.writeValueAsString(Map.of(
                "fromAccountId", alice.accountId(),
                "toAccountId", bob.accountId(),
                "amount", 100,
                "idempotencyKey", idemKey
        ));

        MvcResult createRes = mockMvc.perform(post("/api/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferPayload))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("INITIATED"))
                .andReturn();

        String txId = objectMapper.readTree(createRes.getResponse().getContentAsString())
                .get("transactionId")
                .asText();

        mockMvc.perform(post("/api/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(txId));

        // Sender (Alice), receiver (Bob), and Admin can view transfer; Charlie cannot -> 403
        mockMvc.perform(get("/api/transfers/{txId}", txId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/transfers/{txId}", txId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bob.token()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/transfers/{txId}", txId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/transfers/{txId}", txId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + charlie.token()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminFreezeAndUnfreeze_controlsTransferAbility() throws Exception {
        UserSession alice = registerUser("alice-frz");
        UserSession bob = registerUser("bob-frz");
        String adminToken = login("admin@paymentsys.local", "admin12345");

        // Admin freezes Alice's account
        mockMvc.perform(patch("/api/admin/accounts/{id}/status", alice.accountId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"FROZEN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));

        // Alice cannot transfer while frozen
        mockMvc.perform(post("/api/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fromAccountId", alice.accountId(),
                                "toAccountId", bob.accountId(),
                                "amount", 50,
                                "idempotencyKey", "frz-" + UUID.randomUUID()
                        ))))
                .andExpect(status().isForbidden());

        // Admin unfreezes Alice's account -> transfer succeeds
        mockMvc.perform(patch("/api/admin/accounts/{id}/status", alice.accountId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(post("/api/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fromAccountId", alice.accountId(),
                                "toAccountId", bob.accountId(),
                                "amount", 50,
                                "idempotencyKey", "unfrz-" + UUID.randomUUID()
                        ))))
                .andExpect(status().isAccepted());
    }
}
