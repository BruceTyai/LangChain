package com.localmind.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localmind.dto.AnswerType;
import com.localmind.dto.ChatResponse;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceTest {

    @Test
    void usesModelFallbackAndHidesRetrievedSourcesWhenDecisionRejectsKnowledgeBase() {
        ChatModel chatModel = mock(ChatModel.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        EmbeddingStore<TextSegment> embeddingStore = mock(EmbeddingStore.class);
        when(embeddingModel.embed("值班电话"))
                .thenReturn(Response.from(Embedding.from(new float[] {0.1f})));
        when(embeddingStore.search(any())).thenReturn(searchResult("值班电话：720750", "contact.txt"));
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(modelResponse("{\"answerType\":\"MODEL_FALLBACK\",\"sourceIndexes\":[]}"))
                .thenReturn(modelResponse("这是模型通用知识答案。"));

        ChatResponse response = new ChatService(
                chatModel, embeddingModel, embeddingStore, new ObjectMapper(), 3, 0.8
        ).ask("值班电话");

        assertThat(response.answerType()).isEqualTo(AnswerType.MODEL_FALLBACK);
        assertThat(response.sources()).isEmpty();
        assertThat(response.answer()).isEqualTo("这是模型通用知识答案。");
        verify(chatModel, times(2)).chat(any(ChatRequest.class));
    }

    @Test
    void returnsOnlySourcesSelectedByKnowledgeBaseDecision() {
        ChatModel chatModel = mock(ChatModel.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        EmbeddingStore<TextSegment> embeddingStore = mock(EmbeddingStore.class);
        when(embeddingModel.embed("值班电话"))
                .thenReturn(Response.from(Embedding.from(new float[] {0.1f})));
        when(embeddingStore.search(any())).thenReturn(new EmbeddingSearchResult<>(List.of(
                match("值班电话：720750", "contact.txt"),
                match("无关资料", "other.txt")
        )));
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(modelResponse("{\"answerType\":\"KNOWLEDGE_BASE\",\"sourceIndexes\":[1]}"))
                .thenReturn(modelResponse("值班电话：720750"));

        ChatResponse response = new ChatService(
                chatModel, embeddingModel, embeddingStore, new ObjectMapper(), 3, 0.8
        ).ask("值班电话");

        assertThat(response.answerType()).isEqualTo(AnswerType.KNOWLEDGE_BASE);
        assertThat(response.sources()).extracting(source -> source.name())
                .containsExactly("contact.txt");
        assertThat(response.answer()).isEqualTo("值班电话：720750");
        verify(chatModel, times(2)).chat(any(ChatRequest.class));
    }

    @Test
    void acceptsKnowledgeBaseDecisionWrappedInMarkdownJsonFence() {
        ChatResponse response = askWithDecision("""
                ```json
                {"answerType":"KNOWLEDGE_BASE","sourceIndexes":[1]}
                ```
                """);

        assertThat(response.answerType()).isEqualTo(AnswerType.KNOWLEDGE_BASE);
        assertThat(response.sources()).extracting(source -> source.name())
                .containsExactly("contact.txt");
    }

    @Test
    void extractsDecisionJsonSurroundedByExplanation() {
        ChatResponse response = askWithDecision("判定结果如下：\n{\"answerType\":\"KNOWLEDGE_BASE\",\"sourceIndexes\":[1]}\n请使用资料 1。");

        assertThat(response.answerType()).isEqualTo(AnswerType.KNOWLEDGE_BASE);
    }

    @Test
    void fallsBackWhenDecisionContainsNoJsonObject() {
        ChatResponse response = askWithDecision("资料不足，建议使用模型通用知识。");

        assertThat(response.answerType()).isEqualTo(AnswerType.MODEL_FALLBACK);
        assertThat(response.sources()).isEmpty();
    }

    private static ChatResponse askWithDecision(String decision) {
        ChatModel chatModel = mock(ChatModel.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        EmbeddingStore<TextSegment> embeddingStore = mock(EmbeddingStore.class);
        when(embeddingModel.embed("值班电话"))
                .thenReturn(Response.from(Embedding.from(new float[] {0.1f})));
        when(embeddingStore.search(any())).thenReturn(searchResult("值班电话：720750", "contact.txt"));
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(modelResponse(decision))
                .thenReturn(modelResponse("值班电话：720750"));

        return new ChatService(chatModel, embeddingModel, embeddingStore, new ObjectMapper(), 3, 0.8)
                .ask("值班电话");
    }
    private static EmbeddingSearchResult<TextSegment> searchResult(String text, String source) {
        return new EmbeddingSearchResult<>(List.of(match(text, source)));
    }

    private static EmbeddingMatch<TextSegment> match(String text, String source) {
        return new EmbeddingMatch<>(
                0.9,
                "embedding-id",
                Embedding.from(new float[] {0.1f}),
                TextSegment.from(text, Metadata.from("source", source))
        );
    }

    private static dev.langchain4j.model.chat.response.ChatResponse modelResponse(String text) {
        return dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(AiMessage.from(text))
                .build();
    }
}
