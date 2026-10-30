package com.zz.agent.policy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.nio.file.Path;

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
}
