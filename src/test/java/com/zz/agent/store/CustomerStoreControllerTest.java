package com.zz.agent.store;

import com.zz.agent.workflow.AgentTaskCsrf;
import com.zz.agent.workflow.AgentTaskCsrfFilter;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CustomerStoreControllerTest {

    private static final String COOKIE = "SESSION=customer-demo";

    @Test
    void customerOfferRouteRequiresSameOriginCsrfAndReturnsCandidate() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-11-02T02:00:00Z"), ZoneOffset.UTC);
        AgentTaskCsrf csrf = new AgentTaskCsrf(clock);
        CustomerStoreSelectionService flow = mock(CustomerStoreSelectionService.class);
        UUID taskId = UUID.randomUUID();
        when(flow.requireCustomer(COOKIE)).thenReturn(5001L);
        var options = java.util.List.of(new NearbyStoreAvailabilityService.Option("STORE-A", 1.0,
                NearbyStoreAvailabilityService.Availability.AVAILABLE, true, "snapshot-a",
                clock.instant(), true));
        var offer = new com.zz.agent.workflow.JdbcAgentTaskStore.StoreOffer(UUID.randomUUID(), taskId,
                5001, 0, "MX-7", java.time.OffsetDateTime.parse("2026-11-07T14:00:00+08:00"),
                options, clock.instant().plusSeconds(600));
        when(flow.offer(eq(COOKIE), eq(taskId), eq(0L), any(), eq("MX-7"), any()))
                .thenReturn(offer);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new CustomerStoreController(flow, csrf))
                .addFilters(new AgentTaskCsrfFilter(csrf))
                .defaultRequest(post("/").with(request -> {
                    request.setServerName("localhost");
                    request.setServerPort(8091);
                    return request;
                })).build();
        String body = """
                {"expectedVersion":0,"latitude":31.2304,"longitude":121.4737,
                 "modelCode":"MX-7","appointmentAt":"2026-11-07T14:00:00+08:00"}
                """;
        mvc.perform(get("/api/agent/customer-stores/csrf").header("Cookie", COOKIE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty());
        mvc.perform(post("/api/agent/customer-stores/{taskId}/offers", taskId)
                        .header("Cookie", COOKIE).contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/agent/customer-stores/{taskId}/offers", taskId)
                        .header("Cookie", COOKIE).header("Origin", "http://localhost:8091")
                        .header("X-Agent-CSRF", csrf.issue(COOKIE).token())
                        .contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.options[0].storeId").value("STORE-A"));
    }
}
