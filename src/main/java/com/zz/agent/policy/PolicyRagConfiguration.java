package com.zz.agent.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zz.agent.integration.CarSystemReadClient;
import com.zz.agent.integration.HttpCarSystemReadClient;
import com.zz.agent.model.ModelTextGenerator;
import com.zz.agent.store.NearbyStoreAvailabilityService;
import com.zz.agent.store.NearbyStoreSelector;
import com.zz.agent.store.SimulatedStoreCatalog;
import com.zz.agent.workflow.AgentAdviceOrchestrator;
import com.zz.agent.workflow.AgentTaskWorkflow;
import com.zz.agent.workflow.AgentTaskCsrf;
import com.zz.agent.workflow.AgentTaskCsrfFilter;
import com.zz.agent.workflow.JdbcAgentTaskStore;
import com.zz.agent.workflow.JdbcConfirmationStore;
import com.zz.agent.workflow.ReadOnlyAdviceFlow;
import com.zz.agent.workflow.SimulatedAssignmentPort;
import com.zz.agent.workflow.SimulatedConfirmationFlow;
import com.zz.agent.workflow.StructuredAdviceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;

/** 仅在显式启用政策检索时创建数据库和向量库，不影响默认启动。 */
@Configuration(proxyBeanMethods = false)
@Profile("policy-rag")
public class PolicyRagConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(PolicyRagConfiguration.class);

    @Bean
    DataSource policyDataSource(@Value("${agent.rag.jdbc-url}") String url,
                                @Value("${agent.rag.jdbc-user}") String user,
                                @Value("${agent.rag.jdbc-password}") String password) {
        return new DriverManagerDataSource(url, user, password);
    }

    @Bean
    JdbcTemplate policyJdbcTemplate(DataSource policyDataSource) {
        return new JdbcTemplate(policyDataSource);
    }

    @Bean
    PgVectorStore policyVectorStore(JdbcTemplate policyJdbcTemplate, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(policyJdbcTemplate, embeddingModel)
                .dimensions(1024)
                .indexType(PgVectorStore.PgIndexType.NONE)
                .vectorTableName("policy_vector_store")
                .initializeSchema(true)
                .build();
    }

    @Bean
    PolicyIndexService policyIndexService(PgVectorStore policyVectorStore, JdbcTemplate policyJdbcTemplate,
                                          @Value("${agent.rag.policy-directory}") String directory,
                                          @Value("${agent.rag.embedding-model-id}") String modelId) {
        return new PolicyIndexService(policyVectorStore, policyJdbcTemplate, Path.of(directory), modelId);
    }

    @Bean
    PolicyRagService policyRagService(PgVectorStore policyVectorStore,
                                      @Value("${agent.rag.policy-directory}") String directory) {
        return new PolicyRagService(policyVectorStore, Path.of(directory));
    }

    @Bean
    ApplicationRunner indexSimulatedPolicies(PolicyIndexService index) {
        return args -> {
            PolicyIndexService.Result result = index.refresh();
            LOG.info("模拟政策索引就绪：片段数={}，本次更新={}", result.documents(), result.changed());
        };
    }

    @Bean
    CarSystemReadClient carSystemReadClient(@Value("${agent.car-system.base-url}") String baseUrl) {
        return new HttpCarSystemReadClient(URI.create(baseUrl));
    }

    @Bean
    @Profile("deepseek & bailian-embedding")
    SimulatedStoreCatalog simulatedStoreCatalog() throws IOException {
        return SimulatedStoreCatalog.load(Path.of("data/stores/simulated-v1.json"), Clock.systemUTC());
    }

    @Bean
    @Profile("deepseek & bailian-embedding")
    NearbyStoreAvailabilityService nearbyStoreAvailabilityService(SimulatedStoreCatalog catalog) {
        return new NearbyStoreAvailabilityService(new NearbyStoreSelector(),
                catalog.stores(), catalog, Clock.systemUTC());
    }

    @Bean
    ReadOnlyAdviceFlow readOnlyAdviceFlow(CarSystemReadClient carSystem, PolicyRagService policies) {
        return new ReadOnlyAdviceFlow(carSystem, policies::search, Clock.systemUTC());
    }

    @Bean
    JdbcAgentTaskStore jdbcAgentTaskStore(JdbcTemplate policyJdbcTemplate, ObjectMapper json) {
        return new JdbcAgentTaskStore(policyJdbcTemplate, json);
    }

    @Bean
    JdbcConfirmationStore jdbcConfirmationStore(JdbcTemplate policyJdbcTemplate) {
        return new JdbcConfirmationStore(policyJdbcTemplate);
    }

    @Bean
    ApplicationRunner initializeAgentTaskTable(JdbcAgentTaskStore tasks, JdbcConfirmationStore confirmations) {
        return args -> {
            tasks.initializeSchema();
            confirmations.initializeSchema();
        };
    }

    @Bean
    AgentTaskWorkflow agentTaskWorkflow(CarSystemReadClient carSystem, ReadOnlyAdviceFlow advice,
                                        JdbcAgentTaskStore tasks) {
        return new AgentTaskWorkflow(carSystem, advice, tasks, Clock.systemUTC());
    }

    @Bean
    @Profile("deepseek")
    StructuredAdviceService structuredAdviceService(ModelTextGenerator generator, ObjectMapper json) {
        return new StructuredAdviceService((system, user) -> {
            ChatResponse response = generator.generate(system, user);
            var metadata = response.getMetadata();
            var usage = metadata == null ? null : metadata.getUsage();
            return new StructuredAdviceService.Completion(
                    response.getResult() == null || response.getResult().getOutput() == null
                            ? null : response.getResult().getOutput().getText(),
                    metadata == null ? null : metadata.getModel(),
                    usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens().longValue(),
                    usage == null || usage.getCompletionTokens() == null ? 0
                            : usage.getCompletionTokens().longValue());
        }, json);
    }

    @Bean
    @Profile("deepseek")
    AgentAdviceOrchestrator agentAdviceOrchestrator(AgentTaskWorkflow workflow, StructuredAdviceService advice) {
        return new AgentAdviceOrchestrator(workflow, advice);
    }

    @Bean
    @Profile("deepseek & bailian-embedding")
    AgentTaskCsrf agentTaskCsrf() {
        return new AgentTaskCsrf(Clock.systemUTC());
    }

    @Bean
    @Profile("deepseek & bailian-embedding")
    AgentTaskCsrfFilter agentTaskCsrfFilter(AgentTaskCsrf csrf) {
        return new AgentTaskCsrfFilter(csrf);
    }

    @Bean
    @Profile("deepseek & bailian-embedding")
    SimulatedAssignmentPort simulatedAssignmentPort() {
        // 演示端固定拒绝；不能把只读业务系统接到真实分配。
        return (cookie, workOrderId, advisorId) -> SimulatedAssignmentPort.Result.REJECTED;
    }

    @Bean
    @Profile("deepseek & bailian-embedding")
    SimulatedConfirmationFlow simulatedConfirmationFlow(CarSystemReadClient carSystem,
                                                        ReadOnlyAdviceFlow readOnly,
                                                        JdbcAgentTaskStore tasks,
                                                        JdbcConfirmationStore confirmations,
                                                        SimulatedAssignmentPort simulation) {
        return new SimulatedConfirmationFlow(carSystem, readOnly, tasks, confirmations,
                simulation, Clock.systemUTC());
    }
}
