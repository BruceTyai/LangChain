package com.localmind.service;

import com.localmind.dto.ChatResponse;
import com.localmind.dto.ChatSource;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ChatService {

    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final int maxResults;
    private final double minScore;

    public ChatService(
            ChatModel chatModel,
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore,
            @Value("${app.rag.max-results}") int maxResults,
            @Value("${app.rag.min-score}") double minScore) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.maxResults = maxResults;
        this.minScore = minScore;
    }

    public ChatResponse ask(String question) {
        Embedding queryEmbedding = embeddingModel.embed(question).content();
        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .maxResults(maxResults)
                        .minScore(minScore)
                        .build());

        List<ChatSource> sources = new ArrayList<>();
        StringBuilder context = new StringBuilder();
        int index = 1;
        for (EmbeddingMatch<TextSegment> match : result.matches()) {
            TextSegment segment = match.embedded();
            String source = segment.metadata().getString("source");
            context.append("\n[资料 ").append(index).append(" · ").append(source).append("]\n")
                    .append(segment.text()).append('\n');
            sources.add(new ChatSource(
                    index++,
                    source,
                    match.score(),
                    segment.text().substring(0, Math.min(180, segment.text().length()))));
        }

        String prompt = """
                你是一个严谨的中文知识库助手。仅依据下方资料回答问题；资料不足时明确说“知识库中没有足够信息”，不要编造。
                回答要简洁清晰，并在相关句末用 [资料 1] 的形式标注来源。不要输出思考过程。
                用户问题：%s

                知识库资料：%s
                """.formatted(question, context.length() == 0 ? "（未检索到相关资料）" : context);
        return new ChatResponse(chatModel.chat(prompt), sources);
    }
}

