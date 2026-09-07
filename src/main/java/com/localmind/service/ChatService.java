package com.localmind.service;

import com.localmind.dto.ChatResponse;
import com.localmind.dto.ChatSource;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ChatService {

    private static final Logger log =
            LoggerFactory.getLogger(ChatService.class);

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

        // 1. 将问题向量化
        Embedding queryEmbedding =
                embeddingModel.embed(question).content();

        // 2. 向量检索
        EmbeddingSearchResult<TextSegment> result =
                embeddingStore.search(
                        EmbeddingSearchRequest.builder()
                                .queryEmbedding(queryEmbedding)
                                .maxResults(maxResults)
                                .minScore(minScore)
                                .build()
                );

        List<ChatSource> sources = new ArrayList<>();
        StringBuilder context = new StringBuilder();

        int index = 1;

        for (EmbeddingMatch<TextSegment> match : result.matches()) {

            TextSegment segment = match.embedded();

            String source =
                    segment.metadata().getString("source");

            log.info(
                    "RAG result [{}] score={}, source={}, text={}",
                    index,
                    match.score(),
                    source,
                    segment.text()
            );

            context.append("\n[资料 ")
                    .append(index)
                    .append(" · ")
                    .append(source)
                    .append("]\n")
                    .append(segment.text())
                    .append('\n');

            sources.add(
                    new ChatSource(
                            index,
                            source,
                            match.score(),
                            segment.text().substring(
                                    0,
                                    Math.min(
                                            180,
                                            segment.text().length()
                                    )
                            )
                    )
            );

            index++;
        }

        // 3. 一个相关资料都没有时，不需要调用大模型
        if (sources.isEmpty()) {
            return new ChatResponse(
                    "知识库中没有足够信息。",
                    sources
            );
        }

        // 4. 真正的 SystemMessage
        String systemPrompt = """
        你是技术保障部知识库系统。

        只能根据提供的知识库资料回答问题。

        如果资料中包含用户询问的信息，
        必须直接输出资料中的具体答案。

        电话号码、人名、系统名称、地址、编号等具体信息
        必须原样输出，不得遗漏。

        不要输出引用编号。
        不要输出分析过程。

        只有知识库资料中确实不存在相关信息时，
        才回答“知识库中没有足够信息”。
                """;

        // 5. UserMessage 只放知识和当前问题
        String userPrompt = """
        【知识库资料】
        %s

        【用户问题】
        %s

        请先输出资料中的实际答案，再在答案句末标注引用来源。
                """.formatted(context, question);

        log.info("""
                
                ================= RAG PROMPT =================
                QUESTION:
                {}

                CONTEXT:
                {}
                ==============================================
                """,
                question,
                context
        );

        // 6. 分开 SystemMessage / UserMessage
        ChatRequest request =
                ChatRequest.builder()
                        .messages(
                                SystemMessage.from(systemPrompt),
                                UserMessage.from(userPrompt)
                        )
                        .build();

        dev.langchain4j.model.chat.response.ChatResponse llmResponse =
                chatModel.chat(request);

        String answer =
                llmResponse.aiMessage().text();

        log.info("LLM answer: {}", answer);

        return new ChatResponse(answer, sources);
    }
}