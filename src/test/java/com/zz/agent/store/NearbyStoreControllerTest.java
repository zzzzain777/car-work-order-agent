package com.zz.agent.store;

import com.zz.agent.integration.CarSystemReadClient;
import com.zz.agent.workflow.AgentTaskCsrf;
import com.zz.agent.workflow.AgentTaskCsrfFilter;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NearbyStoreControllerTest {

    private static final String COOKIE = "SESSION=nearby-demo";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-02T02:00:00Z"), ZoneOffset.UTC);
    private static final String BODY = """
            {"latitude":31.230400,"longitude":121.473700,"modelCode":"MX-7",
             "appointmentAt":"2026-11-07T14:00:00+08:00"}
            """;

    @Test
    void administratorCanReadFiveSimulatedOptionsWithCsrfToken() throws Exception {
        Fixture fixture = fixture(2);

        fixture.mvc.perform(post("/api/agent/nearby-stores")
                        .header("Cookie", COOKIE)
                        .header("Origin", "http://localhost:8091")
                        .header("X-Agent-CSRF", fixture.csrf.issue(COOKIE).token())
                        .contentType("application/json").content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].storeId").value("STORE-A"))
                .andExpect(jsonPath("$[1].selectable").value(false))
                .andExpect(jsonPath("$[2].storeId").value("STORE-C"))
                .andExpect(jsonPath("$[3].availability").value("SLOT_FULL"))
                .andExpect(jsonPath("$[4].storeId").value("STORE-E"))
                .andExpect(jsonPath("$[5]").doesNotExist());
    }

    @Test
    void ordinaryUserOrMissingCsrfCannotUseTheAdministratorDemoRoute() throws Exception {
        Fixture ordinary = fixture(0);
        ordinary.mvc.perform(post("/api/agent/nearby-stores")
                        .header("Cookie", COOKIE)
                        .header("Origin", "http://localhost:8091")
                        .header("X-Agent-CSRF", ordinary.csrf.issue(COOKIE).token())
                        .contentType("application/json").content(BODY))
                .andExpect(status().isForbidden());

        Fixture admin = fixture(2);
        admin.mvc.perform(post("/api/agent/nearby-stores")
                        .header("Cookie", COOKIE)
                        .header("Origin", "http://localhost:8091")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isForbidden());
    }

    private static Fixture fixture(int role) throws Exception {
        SimulatedStoreCatalog catalog = SimulatedStoreCatalog.load(
                Path.of("data/stores/simulated-v1.json"), CLOCK);
        NearbyStoreAvailabilityService service = new NearbyStoreAvailabilityService(
                new NearbyStoreSelector(), catalog.stores(), catalog, CLOCK);
        CarSystemReadClient car = mock(CarSystemReadClient.class);
        when(car.currentUser(COOKIE)).thenReturn(new CarSystemReadClient.Identity(5001, role, 0));
        AgentTaskCsrf csrf = new AgentTaskCsrf(CLOCK);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new NearbyStoreController(service, car))
                .addFilters(new AgentTaskCsrfFilter(csrf))
                .defaultRequest(post("/").with(request -> {
                    request.setServerName("localhost");
                    request.setServerPort(8091);
                    return request;
                })).build();
        return new Fixture(mvc, csrf);
    }

    private record Fixture(MockMvc mvc, AgentTaskCsrf csrf) {
    }
}
