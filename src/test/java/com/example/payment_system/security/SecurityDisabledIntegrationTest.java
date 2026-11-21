package com.example.payment_system.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.security.disabled=true",
        "transfer.stream.enabled=false",
        "report.stream.enabled=false",
        "rate-limit.enabled=false"
})
@AutoConfigureMockMvc
class SecurityDisabledIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private long registerAndGetAccountId(String prefix) throws Exception {
        String email = prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        MvcResult reg = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", prefix,
                                "email", email,
                                "password", "password123"
                        ))))
                .andExpect(status().isCreated())
                .andReturn();

        long userId = objectMapper.readTree(reg.getResponse().getContentAsString())
                .get("user").get("id").asLong();

        // Unauthenticated account lookup works when app.security.disabled=true
        MvcResult acct = mockMvc.perform(get("/api/accounts/user/{userId}", userId))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode acctNode = objectMapper.readTree(acct.getResponse().getContentAsString());
        return acctNode.get("id").asLong();
    }

    @Test
    void whenSecurityDisabled_endpointsAllowUnauthenticatedRequests() throws Exception {
        long senderAccountId = registerAndGetAccountId("dis-sender");
        long receiverAccountId = registerAndGetAccountId("dis-receiver");

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mockMvc.perform(get("/api/accounts/{id}", senderAccountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(senderAccountId));

        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fromAccountId", senderAccountId,
                                "toAccountId", receiverAccountId,
                                "amount", 25,
                                "idempotencyKey", "dis-" + UUID.randomUUID()
                        ))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("INITIATED"));
    }
}
