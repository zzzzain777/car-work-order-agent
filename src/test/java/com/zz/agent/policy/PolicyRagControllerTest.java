package com.zz.agent.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证只读接口只展示可追溯的模拟政策，不把候选证据说成预约结果。 */
class PolicyRagControllerTest {

    @Test
    void returnsSimulatedPolicyEvidenceAndSource() throws Exception {
        PolicyRagService service = mock(PolicyRagService.class);
        when(service.search(eq("STORE-A"), eq("MX-7"), any(OffsetDateTime.class), eq("下午能试驾吗")))
                .thenReturn(new PolicyEvidenceGate.Decision(PolicyEvidenceGate.Outcome.CANDIDATES,
                        List.of(new PolicyEvidenceGate.Evidence("chunk-1", "POL-STORE-A-MX-7-TEST", 1,
                                "data/policies/STORE-A-MX-7-test-drive-v1.md#rule-1", "模拟试驾规则"))));
        MockMvc mvc = mvc(service);

        mvc.perform(post("/api/policies/search").contentType(MediaType.APPLICATION_JSON).content("""
                {"storeId":"STORE-A","modelCode":"MX-7", "appointmentAt":"2026-11-07T14:00:00+08:00",
                 "question":"下午能试驾吗"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulated").value(true))
                .andExpect(jsonPath("$.outcome").value("CANDIDATES"))
                .andExpect(jsonPath("$.evidence[0].policyId").value("POL-STORE-A-MX-7-TEST"))
                .andExpect(jsonPath("$.evidence[0].sourceRef")
                        .value("data/policies/STORE-A-MX-7-test-drive-v1.md#rule-1"));
    }

    @Test
    void rejectsMissingConfirmedBusinessFields() throws Exception {
        MockMvc mvc = mvc(mock(PolicyRagService.class));

        mvc.perform(post("/api/policies/search").contentType(MediaType.APPLICATION_JSON).content("""
                {"storeId":"","modelCode":"MX-7","appointmentAt":"2026-11-07T14:00:00+08:00",
                 "question":"试驾规则"}
                """))
                .andExpect(status().isBadRequest());
    }

    private static MockMvc mvc(PolicyRagService service) {
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        return MockMvcBuilders.standaloneSetup(new PolicyRagController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .build();
    }
}
