package com.zz.agent.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 预览接口只读且默认不能执行工单分配。 */
class ReadOnlyAdviceControllerTest {

    @Test
    void missingCookieIsRejectedBeforeReadingWorkOrder() throws Exception {
        ReadOnlyAdviceFlow flow = mock(ReadOnlyAdviceFlow.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ReadOnlyAdviceController(flow)).build();

        mvc.perform(get("/api/agent/preview").param("workOrderId", "1001"))
                .andExpect(status().isUnauthorized());
        verify(flow, never()).preview(eq(1001L), any(), any());
    }

    @Test
    void returnsReadOnlyPreviewWithAssignmentDisabled() throws Exception {
        ReadOnlyAdviceFlow flow = mock(ReadOnlyAdviceFlow.class);
        when(flow.preview(eq(1001L), eq("SESSION=simulated-session"), any()))
                .thenReturn(new ReadOnlyAdviceFlow.Result(ReadOnlyAdviceFlow.Stage.READY_FOR_ADMIN_REVIEW,
                        1001, "请核对政策与顾问门店归属", List.of(), List.of(),
                        List.of(new ReadOnlyAdviceFlow.AdvisorLoad(301, "模拟顾问甲", 1, 2, false)),
                        Instant.parse("2026-11-01T02:00:00Z"), false));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ReadOnlyAdviceController(flow)).build();

        mvc.perform(get("/api/agent/preview")
                        .header("Cookie", "SESSION=simulated-session")
                        .param("workOrderId", "1001")
                        .param("storeId", "STORE-A")
                        .param("modelCode", "MX-7")
                        .param("appointmentAt", "2026-11-07T14:00:00+08:00"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.stage").value("READY_FOR_ADMIN_REVIEW"))
                .andExpect(jsonPath("$.assignmentEnabled").value(false))
                .andExpect(jsonPath("$.advisors[0].storeAffiliationVerified").value(false));
    }

    @Test
    void invalidTimeIsRejectedWithoutCallingFlow() throws Exception {
        ReadOnlyAdviceFlow flow = mock(ReadOnlyAdviceFlow.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ReadOnlyAdviceController(flow)).build();

        mvc.perform(get("/api/agent/preview")
                        .header("Cookie", "SESSION=simulated-session")
                        .param("workOrderId", "1001")
                        .param("appointmentAt", "下周末"))
                .andExpect(status().isBadRequest());
        verify(flow, never()).preview(eq(1001L), any(), any());
    }
}
