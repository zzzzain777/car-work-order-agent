package com.zz.agent.probe;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencySmokeTest {

    @Test
    void java21AndSelectedAiTypesAreAvailableTogether() {
        assertEquals(21, Runtime.version().feature());
        assertTrue(ChatModel.class.isAssignableFrom(DashScopeChatModel.class));
        assertTrue(EmbeddingModel.class.isInterface());
        assertEquals("org.springframework.ai.vectorstore.pgvector.PgVectorStore", PgVectorStore.class.getName());
    }
}
