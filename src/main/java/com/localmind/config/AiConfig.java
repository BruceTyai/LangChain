package com.localmind.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaApiVersion;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {
    @Bean ChatModel chatModel(@Value("${app.ollama.base-url}") String url, @Value("${app.ollama.chat-model}") String model) {
        return OllamaChatModel.builder().baseUrl(url).modelName(model).temperature(0.2).timeout(Duration.ofMinutes(5)).build();
    }
    @Bean EmbeddingModel embeddingModel(@Value("${app.ollama.base-url}") String url, @Value("${app.ollama.embedding-model}") String model) {
        return OllamaEmbeddingModel.builder().baseUrl(url).modelName(model).timeout(Duration.ofMinutes(3)).build();
    }
    @Bean EmbeddingStore<TextSegment> embeddingStore(@Value("${app.chroma.base-url}") String url,
            @Value("${app.chroma.collection}") String collection, @Value("${app.chroma.tenant}") String tenant,
            @Value("${app.chroma.database}") String database) {
        return ChromaEmbeddingStore.builder().apiVersion(ChromaApiVersion.V2).baseUrl(url).tenantName(tenant)
                .databaseName(database).collectionName(collection).build();
    }
}
