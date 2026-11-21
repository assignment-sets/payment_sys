package com.example.payment_system.controller;

import com.example.payment_system.exception.InsufficientBalanceException;
import com.example.payment_system.service.TransferProcessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "transfer.stream.enabled=false",
        "report.stream.enabled=false",
        "rate-limit.enabled=false"
})
@AutoConfigureMockMvc
class ApiEdgeCasesIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransferProcessor transferProcessor;

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
                .andReturn();

        JsonNode body = objectMapper.readTree(res.getResponse().getContentAsString());
        long userId = body.get("user").get("id").asLong();
        String token = body.get("accessToken").asText();

        MvcResult acctRes = mockMvc.perform(get("/api/accounts/user/{userId}", userId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
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
                .andReturn();

        return objectMapper.readTree(res.getResponse().getContentAsString())
                .get("accessToken")
                .asText();
    }

    @Test
    void registerDuplicateEmail_returns409Conflict() throws Exception {
        UserSession existing = registerUser("dup-edge");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Duplicate User",
                                "email", existing.email(),
                                "password", "password123"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message", containsString(existing.email())));

        // Case-insensitive duplicate email is also rejected
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Duplicate Upper",
                                "email", existing.email().toUpperCase(),
                                "password", "password123"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void loginWrongPasswordOrUnknownEmail_returns401Unauthorized() throws Exception {
        UserSession user = registerUser("auth-fail");

        // Wrong password -> 401
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", user.email(),
                                "password", "wrong-password"
                        ))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));

        // Unknown email -> 401
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", uniqueEmail("unknown"),
                                "password", "password123"
                        ))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void validationErrors_malformedJsonAndInvalidFields_return400BadRequest() throws Exception {
        UserSession alice = registerUser("val-alice");
        UserSession bob = registerUser("val-bob");
        String adminToken = login("admin@paymentsys.local", "admin12345");

        // Malformed JSON -> 400
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Malformed request body"));

        // Blank/invalid register fields -> 400 with fieldErrors
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", " ",
                                "email", "not-an-email",
                                "password", "short"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.name").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors.email").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors.password").isNotEmpty());

        // Blank login fields -> 400 with fieldErrors
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "bad-email",
                                "password", ""
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.email").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors.password").isNotEmpty());

        // Negative transfer amount -> 400
        mockMvc.perform(post("/api/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fromAccountId", alice.accountId(),
                                "toAccountId", bob.accountId(),
                                "amount", -50,
                                "idempotencyKey", "neg-" + UUID.randomUUID()
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.amount").isNotEmpty());

        // Zero transfer amount + blank idempotencyKey + non-positive toAccountId -> 400
        mockMvc.perform(post("/api/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fromAccountId", alice.accountId(),
                                "toAccountId", 0,
                                "amount", 0,
                                "idempotencyKey", "   "
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.toAccountId").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors.amount").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors.idempotencyKey").isNotEmpty());

        // Path variable @Positive constraint violation -> 400
        mockMvc.perform(get("/api/users/{id}", 0)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation failed"));

        mockMvc.perform(get("/api/accounts/{id}", -1)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation failed"));

        // Path variable type mismatch -> 400
        mockMvc.perform(get("/api/transfers/{txId}", "not-a-uuid")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(get("/api/users/{id}", "not-a-number")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        // Report validation: fromDate after toDate -> 400
        LocalDate today = LocalDate.now();
        mockMvc.perform(post("/api/accounts/{accountId}/reports", alice.accountId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fromDate", today.toString(),
                                "toDate", today.minusDays(5).toString()
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("fromDate must not be after toDate"));

        // Report validation: range > 366 days -> 400
        mockMvc.perform(post("/api/accounts/{accountId}/reports", alice.accountId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fromDate", today.minusDays(400).toString(),
                                "toDate", today.toString()
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void notFoundResources_asAdmin_return404NotFound() throws Exception {
        String adminToken = login("admin@paymentsys.local", "admin12345");
        UUID missingUuid = UUID.randomUUID();

        mockMvc.perform(get("/api/users/{id}", 999_999_999L)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        mockMvc.perform(get("/api/accounts/{id}", 999_999_999L)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        mockMvc.perform(get("/api/accounts/user/{userId}", 999_999_999L)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        mockMvc.perform(get("/api/transfers/{txId}", missingUuid)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        mockMvc.perform(get("/api/reports/{reportId}", missingUuid)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        mockMvc.perform(get("/api/reports/{reportId}/download", missingUuid)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void transferIdempotencyReplay_andInsufficientBalanceFailure() throws Exception {
        UserSession alice = registerUser("xfer-alice");
        UserSession bob = registerUser("xfer-bob");

        // Initial transfer -> 202 Accepted with Location header
        String idemKey = "idem-" + UUID.randomUUID();
        String payload = objectMapper.writeValueAsString(Map.of(
                "fromAccountId", alice.accountId(),
                "toAccountId", bob.accountId(),
                "amount", 250,
                "idempotencyKey", idemKey
        ));

        MvcResult createRes = mockMvc.perform(post("/api/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.LOCATION, containsString("/api/transfers/")))
                .andExpect(jsonPath("$.status").value("INITIATED"))
                .andExpect(jsonPath("$.amount").value(250))
                .andReturn();

        String txId = objectMapper.readTree(createRes.getResponse().getContentAsString())
                .get("transactionId")
                .asText();

        // Idempotency replay -> 200 OK with same transactionId
        mockMvc.perform(post("/api/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(txId))
                .andExpect(jsonPath("$.status").value("INITIATED"))
                .andExpect(jsonPath("$.amount").value(250));

        // Oversized transfer (balance is 1000 cents, request 5000 cents) is accepted asynchronously
        // as INITIATED, then fails money movement with InsufficientBalanceException (402 Payment Required)
        MvcResult oversizedRes = mockMvc.perform(post("/api/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fromAccountId", alice.accountId(),
                                "toAccountId", bob.accountId(),
                                "amount", 5000,
                                "idempotencyKey", "oversized-" + UUID.randomUUID()
                        ))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("INITIATED"))
                .andReturn();

        UUID oversizedTxId = UUID.fromString(
                objectMapper.readTree(oversizedRes.getResponse().getContentAsString())
                        .get("transactionId")
                        .asText()
        );

        assertThatThrownBy(() -> transferProcessor.executeMoneyMovement(oversizedTxId))
                .isInstanceOf(InsufficientBalanceException.class)
                .satisfies(ex -> assertThat(((InsufficientBalanceException) ex).getStatus())
                        .isEqualTo(HttpStatus.PAYMENT_REQUIRED));
    }
}
