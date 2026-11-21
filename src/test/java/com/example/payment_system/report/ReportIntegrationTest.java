package com.example.payment_system.report;

import com.example.payment_system.entity.ReportStatus;
import com.example.payment_system.service.ReportProcessor;
import com.example.payment_system.stream.ReportStreamProducer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "transfer.stream.enabled=false",
        "report.stream.enabled=false",
        "rate-limit.enabled=false",
        "cache.account.notify.enabled=false"
})
@AutoConfigureMockMvc
class ReportIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ReportProcessor reportProcessor;

    @MockitoBean
    private ReportStreamProducer reportStreamProducer;

    @Test
    void pastAndCurrentReportFlows_workEndToEndWithOwnershipAndCsvDownload() throws Exception {
        String email = "reporter-" + UUID.randomUUID() + "@test.local";
        String token = registerAndGetToken(email);
        long accountId = getMyAccountId(token);

        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        LocalDate twoDaysAgo = yesterday.minusDays(1);

        // 1. Past report: synchronous 200 OK + frozen snapshot on repeat request
        String pastBody = objectMapper.writeValueAsString(Map.of(
                "fromDate", twoDaysAgo.toString(),
                "toDate", yesterday.toString()
        ));

        MvcResult firstPast = mockMvc.perform(post("/api/accounts/{id}/reports", accountId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pastBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("FROZEN_SNAPSHOT"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andReturn();

        String pastReportId = objectMapper.readTree(firstPast.getResponse().getContentAsString())
                .get("reportId").asText();

        mockMvc.perform(post("/api/accounts/{id}/reports", accountId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pastBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportId").value(pastReportId))
                .andExpect(jsonPath("$.source").value("FROZEN_SNAPSHOT"));

        // 2. Current report: 202 Accepted + Location -> worker processes -> 200 COMPLETED + CSV download
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String currentBody = objectMapper.writeValueAsString(Map.of(
                "fromDate", today.toString(),
                "toDate", today.toString()
        ));

        MvcResult queuedResult = mockMvc.perform(post("/api/accounts/{id}/reports", accountId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(currentBody))
                .andExpect(status().isAccepted())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.status").value(ReportStatus.PENDING.name()))
                .andExpect(jsonPath("$.source").value("ASYNC_QUEUE"))
                .andReturn();

        UUID currentReportId = UUID.fromString(
                objectMapper.readTree(queuedResult.getResponse().getContentAsString())
                        .get("reportId").asText());
        verify(reportStreamProducer).publish(currentReportId);

        // Download before completion -> 409 Conflict
        mockMvc.perform(get("/api/reports/{reportId}/download", currentReportId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isConflict());

        // Simulate async worker execution
        reportProcessor.process(currentReportId);

        mockMvc.perform(get("/api/reports/{reportId}", currentReportId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.entryCount").value(1))
                .andExpect(jsonPath("$.closingBalance").value(1000));

        MvcResult csvResult = mockMvc.perform(get("/api/reports/{reportId}/download", currentReportId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, org.hamcrest.Matchers.startsWith("text/csv")))
                .andReturn();

        assertThat(csvResult.getResponse().getContentAsString())
                .contains("accountId,fromDate,toDate,source,openingBalance,closingBalance")
                .contains("CREDIT,1000");

        // 3. Another user cannot access this report -> 403 Forbidden
        String otherToken = registerAndGetToken("other-" + UUID.randomUUID() + "@test.local");
        mockMvc.perform(get("/api/reports/{reportId}", currentReportId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isForbidden());
    }

    private String registerAndGetToken(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Reporter",
                                "email", email,
                                "password", "password123"
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("accessToken").asText();
    }

    private long getMyAccountId(String token) throws Exception {
        MvcResult me = mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        long userId = objectMapper.readTree(me.getResponse().getContentAsString()).get("id").asLong();

        MvcResult acc = mockMvc.perform(get("/api/accounts/user/{userId}", userId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(acc.getResponse().getContentAsString()).get("id").asLong();
    }
}
